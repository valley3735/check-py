package com.chao;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PythonHealthChecker {

    // 【请设置】验证目标的 Python 项目根目录 (注意：Windows 路径推荐使用 / 或 \\)
    private static final String PROJECT_PATH = "D:/vnpy-master";

    public static void main(String[] args) throws Exception {

        // --- pip 安装 pyflakes 的目录 ---
        File depsDir = new File(PROJECT_PATH, "deps");
        // 确保依赖目录存在
        depsDir.mkdirs();

        // 0. 安装 pyflakes（如未安装）
        ensurePyflakes(depsDir);

        // 1. 扫描所有 Python 文件（排除 deps 目录）
        List<File> pyFiles = scanPyFiles(new File(PROJECT_PATH), depsDir);
        System.out.println("Found python files: " + pyFiles.size());

        List<JsonObject> results = new ArrayList<>();

        for (File py : pyFiles) {
            System.out.println("Checking: " + py.getAbsolutePath());

            // 检查文件健康状态
            JsonObject r = checkFileHealth(py, depsDir);
            results.add(r);

            String status = r.get("status").getAsString();
            String message = r.has("message") ? r.get("message").getAsString() : "";

            if ("ok".equals(status)) {
                System.out.println(" ✔ OK: " + py.getName());
            } else {
                System.out.println(" ✘ DAMAGED: " + py.getName());

                // 【乱码优化】将 WinError 5 乱码替换为明确的中文提示
                String reason = message;
                if (message.contains("WinError 5")) {
                    // Python 脚本中已经返回了英文错误，这里可以保持一致或提供中文翻译
                    reason = "pyflakes_runtime_error: [WinError 5] 拒绝访问 (请检查文件执行权限)";
                }
                System.out.println("     Reason: " + reason);
            }
        }

        // 3. 保存检查结果
        saveReport(PROJECT_PATH, results);
        System.out.println("Report saved at: " + PROJECT_PATH + "/health_report.json");
    }

    // ----------------------- 文件扫描 --------------------------
    private static List<File> scanPyFiles(File root, File depsDir) {
        List<File> result = new ArrayList<>();
        scanRec(root, result, depsDir);
        return result;
    }

    private static void scanRec(File dir, List<File> out, File depsDir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;

        // 过滤 deps 目录
        if (dir.getAbsolutePath().startsWith(depsDir.getAbsolutePath())) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanRec(f, out, depsDir);
            } else if (f.getName().endsWith(".py")) {
                out.add(f);
            }
        }
    }

    // ----------------------- 执行 detect_health.py --------------------------
    private static JsonObject checkFileHealth(File py, File depsDir) throws Exception {

        InputStream in = PythonHealthChecker.class.getClassLoader()
                .getResourceAsStream("detect_health.py");

        if (in == null) {
            throw new RuntimeException("ERROR: detect_health.py NOT FOUND in classpath!");
        }

        Path script = Files.createTempFile("detect_health", ".py");
        // 将资源文件复制到临时文件
        Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);

        // 命令：python [临时脚本路径] [待检查文件路径]
        ProcessBuilder pb = new ProcessBuilder(
                "python",
                script.toString(),
                py.getAbsolutePath()
        );

        // 【关键修改】设置 PYTHONPATH，确保 python -m pyflakes 能找到 deps 目录下的模块
        String oldPath = pb.environment().getOrDefault("PYTHONPATH", "");
        String newPath = depsDir.getAbsolutePath();
        if (!oldPath.isEmpty()) {
            // 在 Windows 上 File.pathSeparator 是分号 ;
            newPath = newPath + File.pathSeparator + oldPath;
        }
        pb.environment().put("PYTHONPATH", newPath);

        pb.redirectErrorStream(true); // 将 stderr 重定向到 stdout，统一读取

        Process p = pb.start();
        String output = readAll(p.getInputStream());
        int exitCode = p.waitFor();

        // 清理临时脚本
        Files.delete(script);

        if (exitCode != 0) {
            System.err.println("【ERROR】: detect_health.py 进程失败 (Exit Code: " + exitCode + ")");
            System.err.println("Output: " + output);
            return JsonParser.parseString("{\"status\":\"process_error\",\"message\":\"detect_health.py process failed (Exit Code: " + exitCode + ")\"}").getAsJsonObject();
        }

        // 使用 Gson 解析 JSON 结果
        return JsonParser.parseString(output.trim()).getAsJsonObject();
    }

    // ----------------------- 安装 pyflakes --------------------------
    private static void ensurePyflakes(File depsDir) throws Exception {

        // 检查 pyflakes 模块目录是否存在作为判断依据
        File checkDir = new File(depsDir, "pyflakes");
        if (checkDir.exists()) {
            System.out.println("pyflakes module directory already exists. Skipping installation.");
            return;
        }

        System.out.println("Installing pyflakes...");
        // 使用 pip install --target 安装 pyflakes
        ProcessBuilder pb = new ProcessBuilder(
                "python",
                "-m", "pip",
                "install",
                "--target", depsDir.getAbsolutePath(),
                "pyflakes"
        );

        pb.redirectErrorStream(true);
        Process p = pb.start();
        String result = readAll(p.getInputStream());
        System.out.println(result);

        if (p.waitFor() != 0) {
            throw new RuntimeException("Failed to install pyflakes: " + result);
        }

        if (checkDir.exists()) {
            System.out.println("pyflakes installation completed.");
        } else {
            throw new RuntimeException("pyflakes installed, but module directory not found in target directory: " + depsDir.getAbsolutePath());
        }
    }

    // ----------------------- 辅助方法 --------------------------
    private static void saveReport(String project, List<JsonObject> results) throws Exception {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.write(
                Paths.get(project, "health_report.json"),
                gson.toJson(results).getBytes("UTF-8")
        );
    }

    private static String readAll(InputStream in) throws Exception {
        // 使用 InputStreamReader 并指定 UTF-8 编码
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s;
        while ((s = r.readLine()) != null) {
            sb.append(s).append("\n");
        }
        return sb.toString();
    }
}