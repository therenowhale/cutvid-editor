#include <filesystem>
#include <fstream>
#include <iostream>
#include <chrono>
#include <algorithm>
#include <vector>
#include <regex>
#include <string>
#include <sstream>
#include <cmath>

#include <onnxruntime_cxx_api.h>
#include <opencv2/dnn.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/videoio.hpp>

extern "C" {
#include <libavformat/avformat.h>
}

namespace {

void event(const std::string& type, const std::string& stage, int percent, const std::string& message) {
    std::cout << "{\"type\":\"" << type << "\",\"stage\":\"" << stage
              << "\",\"percent\":" << percent << ",\"message\":\"" << message << "\"}" << std::endl;
}

void logLine(const std::filesystem::path& output, const std::string& message) {
    const auto log = output.parent_path() / "logs" / (output.stem().string() + ".log");
    std::filesystem::create_directories(log.parent_path());
    std::ofstream stream(log, std::ios::app);
    stream << message << '\n';
}

std::string unescapeJson(std::string value) {
    std::string result;
    result.reserve(value.size());
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (value[index] == '\\' && index + 1 < value.size()) {
            const char escaped = value[++index];
            result += escaped == 'n' ? '\n' : escaped;
        } else {
            result += value[index];
        }
    }
    return result;
}

std::string field(const std::string& json, const std::string& name) {
    const std::regex pattern("\\\"" + name + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    std::smatch match;
    if (!std::regex_search(json, match, pattern)) return {};
    return unescapeJson(match[1].str());
}

int integerField(const std::string& json, const std::string& name, int fallback, int minimum, int maximum) {
    try { return std::clamp(std::stoi(field(json, name)), minimum, maximum); }
    catch (...) { return fallback; }
}

cv::Scalar colorField(const std::string& json, const std::string& name) {
    const std::string value = field(json, name);
    const std::regex hex("^#?([0-9a-fA-F]{6})$");
    std::smatch match;
    if (!std::regex_match(value, match, hex)) return {255, 255, 255};
    const int rgb = std::stoi(match[1].str(), nullptr, 16);
    return {static_cast<double>(rgb & 0xff), static_cast<double>((rgb >> 8) & 0xff), static_cast<double>((rgb >> 16) & 0xff)};
}

std::string shellQuote(const std::filesystem::path& value) {
    std::string quoted = "'";
    for (const char character : value.string()) quoted += character == '\'' ? "'\\\"'\\\"'" : std::string(1, character);
    return quoted + "'";
}

bool muxReferenceAudio(const std::filesystem::path& silentVideo, const std::filesystem::path& reference, const std::filesystem::path& output, std::string& error) {
    const std::filesystem::path ffmpeg = std::filesystem::is_regular_file("/opt/homebrew/opt/ffmpeg/bin/ffmpeg")
        ? "/opt/homebrew/opt/ffmpeg/bin/ffmpeg" : "ffmpeg";
    const std::string command = shellQuote(ffmpeg) + " -y -v error -i " + shellQuote(silentVideo)
        + " -i " + shellQuote(reference) + " -map 0:v:0 -map 1:a? -c:v copy -c:a aac -shortest " + shellQuote(output);
    if (std::system(command.c_str()) != 0) {
        error = "FFmpeg could not mux reference audio into the export.";
        return false;
    }
    return true;
}

double overlap(const cv::Rect& first, const cv::Rect& second) {
    const int intersection = (first & second).area();
    const int combined = first.area() + second.area() - intersection;
    return combined > 0 ? static_cast<double>(intersection) / combined : 0.0;
}

cv::Rect constrainBox(cv::Rect box, const cv::Size& frameSize) {
    box.x = std::clamp(box.x, 0, frameSize.width - 1);
    box.y = std::clamp(box.y, 0, frameSize.height - 1);
    box.width = std::clamp(box.width, 1, frameSize.width - box.x);
    box.height = std::clamp(box.height, 1, frameSize.height - box.y);
    return box;
}

bool inputExists(const std::string& path) {
    return !path.empty() && std::filesystem::is_regular_file(std::filesystem::path(path));
}

std::filesystem::path modelPath(const std::string& configuredPath) {
    if (!configuredPath.empty()) return configuredPath;
    const auto modnet = std::filesystem::current_path() / "models" / "modnet_photographic.onnx";
    return std::filesystem::is_regular_file(modnet) ? modnet : std::filesystem::current_path() / "models" / "u2net_human_seg.onnx";
}

bool verifySegmentationModel(const std::filesystem::path& path, std::string& error) {
    if (!std::filesystem::is_regular_file(path)) {
        error = "Human-segmentation model is missing: " + path.string();
        return false;
    }
    try {
        Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "jamal-render-engine");
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(2);
        Ort::Session session(environment, path.c_str(), options);
        if (session.GetInputCount() == 0 || session.GetOutputCount() == 0) {
            error = "Human-segmentation model has no usable input or output.";
            return false;
        }
    } catch (const Ort::Exception& exception) {
        error = "Could not load human-segmentation model: " + std::string(exception.what());
        return false;
    }
    return true;
}

struct VideoInfo {
    int width{};
    int height{};
    double framesPerSecond{};
    double durationSeconds{};
};

bool probeVideo(const std::string& path, VideoInfo& info, std::string& error) {
    AVFormatContext* format = nullptr;
    if (avformat_open_input(&format, path.c_str(), nullptr, nullptr) < 0) {
        error = "FFmpeg could not open the video.";
        return false;
    }

    const auto close = [&format] { avformat_close_input(&format); };
    if (avformat_find_stream_info(format, nullptr) < 0) {
        error = "FFmpeg could not read video stream information.";
        close();
        return false;
    }

    const int streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (streamIndex < 0) {
        error = "The file has no readable video stream.";
        close();
        return false;
    }

    const AVStream* stream = format->streams[streamIndex];
    info.width = stream->codecpar->width;
    info.height = stream->codecpar->height;
    info.framesPerSecond = av_q2d(stream->avg_frame_rate);
    info.durationSeconds = format->duration == AV_NOPTS_VALUE
        ? 0.0
        : static_cast<double>(format->duration) / AV_TIME_BASE;
    close();
    return true;
}

std::string describe(const std::string& label, const VideoInfo& info) {
    return label + ": " + std::to_string(info.width) + "x" + std::to_string(info.height)
        + ", " + std::to_string(info.framesPerSecond) + " fps, "
        + std::to_string(info.durationSeconds) + " seconds.";
}

bool render(const std::string& referencePath, const std::string& backgroundPath, const std::string& outputPath, const std::filesystem::path& segmentationModel, int outline, int scalePercent, int leftPercent, int bottomPercent, cv::Scalar outlineColor, std::string& error) {
    cv::VideoCapture reference(referencePath), background(backgroundPath);
    if (!reference.isOpened() || !background.isOpened()) { error = "Could not decode input videos."; return false; }
    const int width = static_cast<int>(background.get(cv::CAP_PROP_FRAME_WIDTH));
    const int height = static_cast<int>(background.get(cv::CAP_PROP_FRAME_HEIGHT));
    const double fps = reference.get(cv::CAP_PROP_FPS) > 0 ? reference.get(cv::CAP_PROP_FPS) : 30.0;
    const std::filesystem::path output(outputPath);
    std::filesystem::create_directories(output.parent_path());
    const auto silentOutput = output.parent_path() / (output.stem().string() + ".video-only.mp4");
    cv::VideoWriter writer(silentOutput.string(), cv::VideoWriter::fourcc('a','v','c','1'), fps, {width, height});
    if (!writer.isOpened()) { error = "Could not create MP4 output."; return false; }
    cv::dnn::Net net = cv::dnn::readNetFromONNX(segmentationModel.string());
    const bool isModNet = segmentationModel.filename().string().find("modnet") != std::string::npos;
    cv::Mat frame, back, previousBinary;
    cv::Rect trackedBox;
    bool hasTracking = false;
    const int total = static_cast<int>(reference.get(cv::CAP_PROP_FRAME_COUNT));
    int index = 0;
    while (reference.read(frame)) {
        if (!background.read(back)) { background.set(cv::CAP_PROP_POS_FRAMES, 0); if (!background.read(back)) { error = "Could not loop background video."; return false; } }
        cv::resize(frame, frame, {width, height}); if (back.size() != frame.size()) cv::resize(back, back, frame.size());
        const cv::Size modelSize = isModNet ? cv::Size(512, 512) : cv::Size(320, 320);
        cv::Mat small; cv::resize(frame, small, modelSize);
        cv::Mat blob = isModNet
            ? cv::dnn::blobFromImage(small, 1.0 / 127.5, modelSize, cv::Scalar(127.5, 127.5, 127.5), true, false)
            : cv::dnn::blobFromImage(small, 1.0 / 255.0, modelSize, {}, true, false);
        if (!isModNet) {
            const float means[] = {0.485f, 0.456f, 0.406f}; const float deviations[] = {0.229f, 0.224f, 0.225f};
            for (int channel = 0; channel < 3; ++channel) { float* values = blob.ptr<float>(0, channel); for (int pixel = 0; pixel < 320 * 320; ++pixel) values[pixel] = (values[pixel] - means[channel]) / deviations[channel]; }
        }
        net.setInput(blob);
        cv::Mat output = net.forward();
        cv::Mat raw(modelSize.height, modelSize.width, CV_32F, output.ptr<float>()), mask;
        double low, high; cv::minMaxLoc(raw, &low, &high);
        // U²-Net exports a probability map. Per-frame min/max scaling made small background
        // confidences look like foreground and made the silhouette jump from frame to frame.
        if (low >= 0.0 && high <= 1.0) {
            raw.convertTo(mask, CV_8U, 255.0);
        } else {
            cv::Mat probability = raw.clone();
            for (int row = 0; row < probability.rows; ++row) {
                float* values = probability.ptr<float>(row);
                for (int column = 0; column < probability.cols; ++column) values[column] = 1.0f / (1.0f + std::exp(-std::clamp(values[column], -20.0f, 20.0f)));
            }
            probability.convertTo(mask, CV_8U, 255.0);
        }
        cv::resize(mask, mask, frame.size(), 0, 0, cv::INTER_LINEAR); cv::GaussianBlur(mask, mask, {3,3}, 0);
        // MODNet emits a soft alpha matte. A conservative cut keeps the source background
        // out of the exported sticker while the white ring restores a clean visual edge.
        cv::Mat binary, labels, stats, centroids; cv::threshold(mask, binary, isModNet ? 180 : 160, 255, cv::THRESH_BINARY);
        // Remove thin, occasional false-positive bridges (such as a tree or post touching
        // the silhouette) before selecting the person component.
        cv::morphologyEx(binary, binary, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_ELLIPSE, {9,9}));
        const int count = cv::connectedComponentsWithStats(binary, labels, stats, centroids);
        int best = -1; double bestScore = -1.0;
        for (int component = 1; component < count; ++component) {
            const int area = stats.at<int>(component, cv::CC_STAT_AREA);
            cv::Rect candidate(stats.at<int>(component, cv::CC_STAT_LEFT), stats.at<int>(component, cv::CC_STAT_TOP), stats.at<int>(component, cv::CC_STAT_WIDTH), stats.at<int>(component, cv::CC_STAT_HEIGHT));
            const double lowerFrameBias = 0.35 + centroids.at<double>(component, 1) / std::max(1, frame.rows);
            const double continuity = hasTracking ? 0.20 + 2.0 * overlap(candidate, trackedBox) : lowerFrameBias;
            const double score = area * continuity;
            if (score > bestScore) { bestScore = score; best = component; }
        }
        if (best < 0) { previousBinary.release(); hasTracking = false; writer.write(back); continue; }
        cv::Mat currentMask; cv::compare(labels, best, currentMask, cv::CMP_EQ);
        // Blend only the chosen person mask. This keeps a moving subject responsive while
        // eliminating single-frame segmentation noise.
        if (!previousBinary.empty() && previousBinary.size() == currentMask.size()) {
            cv::Mat smoothed; cv::addWeighted(currentMask, 0.82, previousBinary, 0.18, 0.0, smoothed);
            cv::threshold(smoothed, currentMask, 160, 255, cv::THRESH_BINARY);
        }
        previousBinary = currentMask;
        cv::Rect detected(stats.at<int>(best, cv::CC_STAT_LEFT) - 12, stats.at<int>(best, cv::CC_STAT_TOP) - 12, stats.at<int>(best, cv::CC_STAT_WIDTH) + 24, stats.at<int>(best, cv::CC_STAT_HEIGHT) + 24);
        detected = constrainBox(detected, frame.size());
        if (hasTracking) {
            trackedBox.x = static_cast<int>(0.78 * trackedBox.x + 0.22 * detected.x);
            trackedBox.y = static_cast<int>(0.78 * trackedBox.y + 0.22 * detected.y);
            trackedBox.width = static_cast<int>(0.78 * trackedBox.width + 0.22 * detected.width);
            trackedBox.height = static_cast<int>(0.78 * trackedBox.height + 0.22 * detected.height);
            trackedBox = constrainBox(trackedBox, frame.size());
        } else { trackedBox = detected; hasTracking = true; }
        const cv::Rect box = trackedBox;
        cv::Mat person = frame(box), personMask = currentMask(box);
        const double scale = std::min(2.0, std::max(0.05, scalePercent / 100.0) * height / person.rows);
        cv::resize(person, person, {}, scale, scale); cv::resize(personMask, personMask, person.size());
        const int x = std::clamp(width * leftPercent / 100, 0, std::max(0, width - 1));
        const int y = std::clamp(height - person.rows - height * bottomPercent / 100, 0, std::max(0, height - 1)); cv::Rect destination(x, y, std::min(person.cols, width - x), std::min(person.rows, height - y));
        person = person(cv::Rect(0, 0, destination.width, destination.height)); personMask = personMask(cv::Rect(0, 0, destination.width, destination.height));
        const int outlineSize = std::max(3, outline * 2 + 1); cv::Mat outer, ring; cv::dilate(personMask, outer, cv::getStructuringElement(cv::MORPH_ELLIPSE, {outlineSize, outlineSize})); cv::subtract(outer, personMask, ring);
        cv::Mat layer = back(destination); layer.setTo(outlineColor, ring); person.copyTo(layer, personMask); writer.write(back);
        ++index; if (index % 10 == 0) event("progress", "render", 30 + (60 * index / std::max(1,total)), "Rendering outlined cutout frames.");
    }
    writer.release();
    if (!muxReferenceAudio(silentOutput, referencePath, output, error)) return false;
    std::error_code cleanupError; std::filesystem::remove(silentOutput, cleanupError);
    return true;
}

}  // namespace

int main(int argumentCount, char* arguments[]) {
    if (argumentCount != 2) {
        event("error", "configuration", 0, "Usage: jamal-render-engine <job.json>");
        return 64;
    }

    std::ifstream jobFile(arguments[1]);
    const std::string job{std::istreambuf_iterator<char>(jobFile), std::istreambuf_iterator<char>()};
    if (!jobFile.good() && job.empty()) {
        event("error", "configuration", 0, "Could not read render job.");
        return 66;
    }

    const auto reference = field(job, "referenceVideo");
    const auto background = field(job, "backgroundVideo");
    const auto output = field(job, "outputVideo");
    const auto segmentationModel = modelPath(field(job, "modelPath"));
    std::string error;
    event("progress", "validation", 15, "Validating selected videos.");

    if (!inputExists(reference) || !inputExists(background)) {
        event("error", "validation", 0, "Reference or background video does not exist.");
        return 65;
    }
    if (output.empty()) {
        event("error", "configuration", 0, "Render job does not specify outputVideo.");
        return 65;
    }
    logLine(output, "Render job started.");

    event("progress", "model", 20, "Loading local human-segmentation model.");
    if (!verifySegmentationModel(segmentationModel, error)) {
        event("error", "model", 0, error);
        return 65;
    }

    event("progress", "probe", 30, "Reading reference-video stream information.");
    VideoInfo referenceInfo;
    if (!probeVideo(reference, referenceInfo, error)) {
        event("error", "probe", 0, error);
        return 65;
    }
    event("progress", "probe", 55, describe("Reference", referenceInfo));

    VideoInfo backgroundInfo;
    if (!probeVideo(background, backgroundInfo, error)) {
        event("error", "probe", 0, error);
        return 65;
    }
    event("progress", "probe", 100, describe("Background", backgroundInfo));
    event("progress", "render", 30, "Rendering outlined cutout frames.");
    const int outline = integerField(job, "outlinePixels", 12, 1, 200);
    const int scalePercent = integerField(job, "scalePercent", 46, 5, 200);
    const int leftPercent = integerField(job, "horizontalPercent", 2, 0, 99);
    const int bottomPercent = integerField(job, "bottomPercent", 3, 0, 99);
    if (!render(reference, background, output, segmentationModel, outline, scalePercent, leftPercent, bottomPercent, colorField(job, "outlineColor"), error)) { event("error", "render", 0, error); logLine(output, error); return 70; }
    event("completed", "export", 100, "Export completed: " + output);
    logLine(output, "Export completed: " + output);
    return 0;
}
