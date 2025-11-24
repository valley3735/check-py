package com.chao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PythonVerifier {

    // 【请设置】验证目标的 Python 项目根目录 (注意：Windows 路径分隔符使用双反斜杠 \\ 或单斜杠 /)
    private static final String PROJECT_ROOT_DIR = "D:\\vnpy-master";

    // 临时虚拟环境目录名。将在 Java 应用程序的当前工作目录下创建。
    private static final String VENV_TEMP_DIR_NAME = "temp_py_venv_run_test";

    public static void main(String[] args) {
        File verifierScript = null;
        Path venvPath = Paths.get(VENV_TEMP_DIR_NAME).toAbsolutePath();

        try {
            // 1. 从 resources 提取 Python 脚本 (需要确保 verifier.py 存在于 classpath/resources 目录下)
            verifierScript = extractResource("verifier.py");
            if (verifierScript == null) {
                System.err.println("【FATAL】: verifier.py 提取失败。");
                return;
            }

            // 2. 找到所有要测试的 .py 文件
            List<String> targetFiles = findPyFiles(PROJECT_ROOT_DIR);
            if (targetFiles.isEmpty()) {
                System.out.println("【INFO】: 目标目录 " + PROJECT_ROOT_DIR + " 中未找到任何 .py 文件。");
                return;
            }

            System.out.println("【INFO】: 找到 " + targetFiles.size() + " 个要测试的 .py 文件。");

            // 3. 执行 Python 验证脚本
            String jsonOutput = runPythonVerifier(verifierScript.getAbsolutePath(),
                    PROJECT_ROOT_DIR,
                    venvPath.toString(),
                    targetFiles);

            // 4. 解析结果并输出日志
            if (jsonOutput != null && !jsonOutput.trim().isEmpty()) {
                logResults(jsonOutput);
            } else {
                System.err.println("【ERROR】: verifier.py 未返回任何结果 (JSON输出为空)。");
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("!!! Java 进程执行中发生异常 !!!");
            e.printStackTrace();
        } finally {
            // 5. 辅助清理
            if (verifierScript != null && verifierScript.exists()) {
                verifierScript.delete();
            }
        }
    }

    private static List<String> findPyFiles(String rootDir) throws IOException {
        Path rootPath = Paths.get(rootDir);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            System.err.println("【ERROR】: 项目根目录不存在或不是目录: " + rootDir);
            return new ArrayList<>();
        }

        try (Stream<Path> walk = Files.walk(rootPath)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> !p.toString().contains(".venv") && !p.toString().contains(VENV_TEMP_DIR_NAME))
                    .map(p -> rootPath.relativize(p).toString())
                    .collect(Collectors.toList());
        }
    }

    private static String runPythonVerifier(String scriptPath, String projectDir, String venvPath, List<String> targetFiles) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("python");
        command.add(scriptPath);
        command.add(projectDir);
        command.add(venvPath);
        command.addAll(targetFiles);

        ProcessBuilder pb = new ProcessBuilder(command);

        // ... (省略日志打印和 Process 启动部分，与上一个版本一致) ...

        System.out.println("\n--- 启动 Python 验证进程 ---");
        System.out.println("执行命令 (仅部分展示): " + command.get(0) + " " + command.get(1) + " " + command.get(2) + " ...");
        System.out.println("----------------------------");

        Process process = pb.start();

        // 异步读取 Python 的标准错误输出 (stderr) 并实时打印到 Java 的 stderr
        String errorCharset = Charset.defaultCharset().name();
        System.out.println("【INFO】: 使用编码 " + errorCharset + " 读取 Python 错误日志...");

        new Thread(() -> {
            try (Scanner scanner = new Scanner(new InputStreamReader(process.getErrorStream(), errorCharset))) {
                while (scanner.hasNextLine()) {
                    System.err.println("PY_LOG: " + scanner.nextLine());
                }
            } catch (Exception e) {
                System.err.println("PY_LOG_ERR: 错误读取 Python stderr: " + e.getMessage());
            }
        }).start();

        // 从 Python 的标准输出 (stdout) 中读取最终的 JSON 结果 (必须是 UTF-8)
        StringBuilder jsonOutput = new StringBuilder();
        try (Scanner scanner = new Scanner(process.getInputStream(), "UTF-8")) {
            while (scanner.hasNextLine()) {
                jsonOutput.append(scanner.nextLine());
            }
        }

        int exitCode = process.waitFor();
        System.out.println("--- Python 进程已完成 (退出码: " + exitCode + ") ---");

        if (exitCode != 0) {
            System.err.println("【ERROR】: Python脚本执行失败或依赖安装失败 (退出码: " + exitCode + ")。请检查上面的 PY_LOG 日志获取详情。");
            return null;
        }

        return jsonOutput.toString();
    }

    private static void logResults(String jsonArrayString) {
        System.out.println("\n--- 最终验证结果总结 ---");

        List<String> syntaxErrorFiles = new ArrayList<>();
        List<String> dependencyErrorFiles = new ArrayList<>();
        List<String> runtimeErrorFiles = new ArrayList<>();
        List<String> successfulFiles = new ArrayList<>();

        try {
            // 简易 JSON 解析
            String content = jsonArrayString.trim().substring(1, jsonArrayString.length() - 1);
            String[] results = content.split("},\\s*\\{");

            for (String resultPart : results) {
                String fullResult = (resultPart.startsWith("{") ? resultPart : "{" + resultPart) +
                        (resultPart.endsWith("}") ? "" : "}");

                String file = extractValue(fullResult, "file");
                String status = extractValue(fullResult, "status");
                String error = extractValue(fullResult, "error");
                String reason = error != null ? error.replace("\n", "\n        ") : "无详情";

                switch (status) {
                    case "SUCCESS":
                        successfulFiles.add(file);
                        break;
                    case "SYNTAX_ERROR":
                        // 这是您人为破坏导致的错误，优先捕获
                        syntaxErrorFiles.add(file + "\n        (原因: " + reason + ")");
                        break;
                    case "DEPENDENCY_FAILURE":
                        // 这是 ModuleNotFoundError 等依赖问题
                        dependencyErrorFiles.add(file + "\n        (原因: " + reason + ")");
                        break;
                    case "RUNTIME_FAILURE":
                    case "FAILURE":
                        // 运行时错误或超时等
                        runtimeErrorFiles.add(file + "\n        (原因: " + reason + ")");
                        break;
                }
            }
        } catch (Exception e) {
            System.err.println("【ERROR】: 解析 JSON 结果时发生错误: " + e.getMessage());
            return;
        }


        System.out.println("\n✅ 成功运行的文件 (能被正常导入/初始化) (" + successfulFiles.size() + " 个):");
        successfulFiles.forEach(f -> System.out.println("    - " + f));

        System.out.println("\n------------------------------------------------------------");
        System.out.println("❌ 失败文件分类报告 (优先处理 SYNTAX_ERROR)");
        System.out.println("------------------------------------------------------------");

        System.out.println("\n🔴 语法错误 - SYNTAX_ERROR (最可能由您修改导致) (" + syntaxErrorFiles.size() + " 个):");
        syntaxErrorFiles.forEach(f -> System.out.println("    - " + f));

        System.out.println("\n🟡 依赖错误 - DEPENDENCY_FAILURE (如 ModuleNotFoundError) (" + dependencyErrorFiles.size() + " 个):");
        dependencyErrorFiles.forEach(f -> System.out.println("    - " + f));

        System.out.println("\n⚫ 其他运行时错误或超时 (" + runtimeErrorFiles.size() + " 个):");
        runtimeErrorFiles.forEach(f -> System.out.println("    - " + f));

        System.out.println("----------------------");
    }

    private static File extractResource(String resourceName) throws IOException {
        InputStream is = PythonVerifier.class.getResourceAsStream("/" + resourceName);
        if (is == null) {
            return null;
        }

        File tempFile = File.createTempFile("verifier", ".py");
        tempFile.deleteOnExit();

        try (OutputStream os = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        } finally {
            is.close();
        }
        return tempFile;
    }

    private static String extractValue(String json, String key) {
        String searchKey = "\"" + key + "\": ";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return null;

        startIndex += searchKey.length();
        int startQuote = json.indexOf("\"", startIndex);
        if (startQuote == -1) return null;

        int endQuote = -1;
        int current = startQuote + 1;
        while (current < json.length()) {
            if (json.charAt(current) == '"' && json.charAt(current - 1) != '\\') {
                endQuote = current;
                break;
            }
            current++;
        }

        if (endQuote == -1) return null;

        String value = json.substring(startQuote + 1, endQuote);
        return value.replace("\\\"", "\"");
    }
}