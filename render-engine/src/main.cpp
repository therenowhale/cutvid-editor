#include <filesystem>
#include <fstream>
#include <iostream>
#include <regex>
#include <string>

namespace {

void event(const std::string& type, const std::string& stage, int percent, const std::string& message) {
    std::cout << "{\"type\":\"" << type << "\",\"stage\":\"" << stage
              << "\",\"percent\":" << percent << ",\"message\":\"" << message << "\"}" << std::endl;
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

bool inputExists(const std::string& path) {
    return !path.empty() && std::filesystem::is_regular_file(std::filesystem::path(path));
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
    event("progress", "validation", 15, "Validating selected videos.");

    if (!inputExists(reference) || !inputExists(background)) {
        event("error", "validation", 0, "Reference or background video does not exist.");
        return 65;
    }

    event("progress", "validation", 100, "Video inputs are ready for segmentation.");
    event("completed", "validation", 100, "Inputs validated. Video rendering is not implemented yet.");
    return 0;
}
