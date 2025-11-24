package com.chao;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Python 项目运行检测器（JDK8）
 *
 * Usage:
 * java -cp <yourclasspath> com.chao.PythonProjectChecker <projectPath> "<extraInstallCmd>" "<csvOutputPath>"
 *
 * - projectPath: 必需，Python 项目根目录（必须包含 requirements.txt）
 * - extraInstallCmd: 可选，额外依赖安装命令（例如 "python init_env.py" 或内网 pip 命令）。传 "" 表示不执行。
 * - csvOutputPath: 可选，如果为空或未提供，则不生成 csv。
 */
@Slf4j
public class PythonProjectChecker {
    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.out.println("Usage: java ... PythonProjectChecker <projectPath> <extraInstallCmd> <csvOutputPath>");
                return;
            }

            String projectPath = args[0];
            String extraInstallCmd = args.length >= 2 ? args[1] : "";
            String csvOutputPath = args.length >= 3 ? args[2] : "";

            File projectDir = new File(projectPath);
            if (!projectDir.exists() || !projectDir.isDirectory()) {
                log.error("Project path not found or not a directory: {}", projectPath);
                return;
            }

            // 确保 requirements.txt 存在
            File requirements = new File(projectDir, "requirements.txt");
            if (!requirements.exists()) {
                log.error("requirements.txt not found in project root: {}", projectPath);
                return;
            }

            // 1) 执行额外安装命令（可选）
            if (extraInstallCmd != null && extraInstallCmd.trim().length() > 0) {
                log.info("Executing extra install command: {}", extraInstallCmd);
                try {
                    runCommandString(extraInstallCmd, projectDir);
                    log.info("Extra install command finished.");
                } catch (Exception ex) {
                    log.error("Extra install command failed: {}", ex.getMessage(), ex);
                    // 继续执行后续 pip 安装（按你要求先执行传入的依赖下载）
                }
            }

            // 2) 使用 pip 安装 requirements.txt（会安装到系统 / 当前 Python 的 site-packages）
            log.info("Installing pip dependencies from {} ...", requirements.getAbsolutePath());
            try {
                List<String> pipCmd = Arrays.asList("python", "-m", "pip", "install", "-r", requirements.getAbsolutePath());
                String pipOut = runCommandAndGetOutput(pipCmd, projectDir);
                log.info("pip install output:\n{}", pipOut);
            } catch (Exception ex) {
                log.error("pip install failed: {}", ex.getMessage(), ex);
                // 如果安装失败依然继续：因为可能部分脚本不依赖第三方库
            }

            // 3) 扫描项目中的所有 .py 文件
            List<File> pyFiles = scanPyFiles(projectDir);
            log.info("Found {} python files to check.", pyFiles.size());

            // 4) 并行执行所有 py 文件（线程数 = CPU 核数）
            List<CheckResult> results = runFilesParallel(pyFiles);

            // 5) 按需输出 CSV（如果 csvOutputPath 非空）
            if (csvOutputPath != null && csvOutputPath.trim().length() > 0) {
                try {
                    writeCsv(csvOutputPath, results);
                    log.info("CSV report generated at: {}", csvOutputPath);
                } catch (Exception ex) {
                    log.error("Failed to write CSV: {}", ex.getMessage(), ex);
                }
            } else {
                log.info("CSV path not provided, skip CSV output.");
            }

        } catch (Throwable t) {
            log.error("Fatal error in PythonProjectChecker: {}", t.getMessage(), t);
        }
    }

    // 扫描 .py 文件（递归）
    private static List<File> scanPyFiles(File root) {
        List<File> list = new ArrayList<>();
        scanRec(root, list);
        return list;
    }

    private static void scanRec(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                // 可以在这里过滤隐藏目录或虚拟环境目录（例如 venv、.venv、env、deps 等），如果需要可加
                String name = f.getName();
                if ("venv".equalsIgnoreCase(name) || ".venv".equalsIgnoreCase(name) || "env".equalsIgnoreCase(name) || "deps".equalsIgnoreCase(name) || "site-packages".equalsIgnoreCase(name)) {
                    // skip virtual env folders
                    continue;
                }
                scanRec(f, out);
            } else if (f.isFile() && f.getName().endsWith(".py")) {
                out.add(f);
            }
        }
    }

    // 并行执行
    private static List<CheckResult> runFilesParallel(List<File> files) throws InterruptedException {
        int threads = Runtime.getRuntime().availableProcessors();
        log.info("Using {} threads for parallel execution.", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Future<CheckResult>> futures = new ArrayList<>();
        for (File f : files) {
            futures.add(pool.submit(() -> runSingleFileWithFollowup(f)));
        }

        List<CheckResult> results = new ArrayList<>();
        for (Future<CheckResult> fut : futures) {
            try {
                results.add(fut.get());
            } catch (ExecutionException ex) {
                log.error("Task execution failed: {}", ex.getMessage(), ex);
                // 转换成 error 结果记录
                Throwable cause = ex.getCause();
                results.add(new CheckResult("unknown", "error", cause == null ? ex.getMessage() : cause.getMessage()));
            }
        }

        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        return results;
    }

    // 执行单个 python 文件并根据失败情况做语法检查
    private static CheckResult runSingleFileWithFollowup(File pyFile) {
        String path = pyFile.getAbsolutePath();
        log.info("Running file: {}", path);

        try {
            List<String> runCmd = Arrays.asList("python", path);
            ProcessResult runRes = runCommandCapture(runCmd, pyFile.getParentFile(), 0 /*0 = no specific timeout*/);

            if (runRes.exitCode == 0) {
                log.info("OK  {}", path);
                return new CheckResult(path, "ok", "");
            } else {
                // 运行失败：获取 stdout/stderr
                String combined = runRes.output;

                log.warn("Runtime failure for {}. Output:\n{}", path, combined);

                // 进一步做语法检查（ast.parse），如果语法 OK -> 记录 ok（按你的要求）
                SyntaxCheckResult syn = doPythonSyntaxCheck(pyFile);
                if (syn.syntaxOk) {
                    // 说明失败是外部资源导致（DB/网络），按要求输出 OK，但把运行错误也记录到日志
                    log.info("After syntax check OK -> treat as ok (external failure). File: {}\nRuntime output:\n{}", path, combined);
                    return new CheckResult(path, "ok", combined);
                } else {
                    // 语法错误 -> 报 error
                    log.error("Syntax error in {}: {}", path, syn.message);
                    return new CheckResult(path, "error", syn.message + " | runtime output: " + truncate(combined, 1000));
                }
            }
        } catch (Exception ex) {
            log.error("Exception when checking file {}: {}", path, ex.getMessage(), ex);
            return new CheckResult(path, "error", ex.getMessage());
        }
    }

    // 执行指定命令并捕获输出（用于 pip install / extraInstall）
    private static String runCommandAndGetOutput(List<String> cmd, File workingDir) throws Exception {
        ProcessResult pr = runCommandCapture(cmd, workingDir, 0);
        if (pr.exitCode != 0) {
            throw new RuntimeException("Command failed: exit=" + pr.exitCode + "\n" + pr.output);
        }
        return pr.output;
    }

    // 执行命令字符串（带空格）例如 "python init_env.py"
    private static void runCommandString(String command, File workingDir) throws Exception {
        List<String> cmd = parseCommand(command);
        ProcessResult pr = runCommandCapture(cmd, workingDir, 0);
        if (pr.exitCode != 0) {
            throw new RuntimeException("Command failed: exit=" + pr.exitCode + "\n" + pr.output);
        }
    }

    // 运行命令并捕获 stdout/stderr。timeoutSeconds=0 表示不超时
    private static ProcessResult runCommandCapture(List<String> cmd, File workingDir, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();

        // 读取线程
        Thread reader = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) != -1) {
                    bout.write(buf, 0, r);
                }
            } catch (IOException ignored) {}
        });
        reader.start();

        boolean finished;
        if (timeoutSeconds > 0) {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                reader.join(1000);
                return new ProcessResult(-1, "Timeout");
            }
        } else {
            int exit = p.waitFor();
            reader.join();
            String out = new String(bout.toByteArray(), "UTF-8");
            return new ProcessResult(exit, out);
        }

        // unreachable for timeoutSeconds == 0 path
        int exit = p.exitValue();
        reader.join();
        String out = new String(bout.toByteArray(), "UTF-8");
        return new ProcessResult(exit, out);
    }

    // 语法检查：调用 python -c "import ast,sys; ..." filePath
    private static SyntaxCheckResult doPythonSyntaxCheck(File file) {
        try {
            // Python code: read file from argv[1], parse, print SYNTAX_OK or SYNTAX_ERR:<msg>
            String code =
                    "import ast,sys\n" +
                            "p=sys.argv[1]\n" +
                            "try:\n" +
                            "    s=open(p,'r',encoding='utf-8').read()\n" +
                            "except Exception as e:\n" +
                            "    print('SYNTAX_ERR:cannot_read:'+str(e))\n" +
                            "    sys.exit(2)\n" +
                            "try:\n" +
                            "    ast.parse(s)\n" +
                            "    print('SYNTAX_OK')\n" +
                            "except SyntaxError as e:\n" +
                            "    msg = 'SyntaxError:'+str(e.msg)+':line:'+str(e.lineno)\n" +
                            "    print('SYNTAX_ERR:'+msg)\n" +
                            "    sys.exit(2)\n" +
                            "except Exception as e:\n" +
                            "    print('SYNTAX_ERR:'+str(e))\n" +
                            "    sys.exit(2)\n";

            List<String> cmd = Arrays.asList("python", "-c", code, file.getAbsolutePath());
            ProcessResult pr = runCommandCapture(cmd, file.getParentFile(), 10);

            String out = pr.output == null ? "" : pr.output.trim();
            if (out.contains("SYNTAX_OK")) {
                return new SyntaxCheckResult(true, "");
            } else if (out.startsWith("SYNTAX_ERR:")) {
                return new SyntaxCheckResult(false, out.substring("SYNTAX_ERR:".length()));
            } else {
                // 未知输出，但 exit code !=0 或其他
                if (pr.exitCode == 0) {
                    return new SyntaxCheckResult(true, "");
                } else {
                    return new SyntaxCheckResult(false, "unknown_syntax_error: " + out);
                }
            }
        } catch (Exception ex) {
            return new SyntaxCheckResult(false, "syntax_check_exception: " + ex.getMessage());
        }
    }

    // 将结果写 CSV
    private static void writeCsv(String csvPath, List<CheckResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("file,status,message\n");
        for (CheckResult r : results) {
            String msg = r.message == null ? "" : r.message.replace("\"", "\"\"");
            sb.append("\"").append(r.file).append("\",")
                    .append(r.status).append(",")
                    .append("\"").append(msg).append("\"\n");
        }
        Files.write(Paths.get(csvPath), sb.toString().getBytes("UTF-8"));
    }

    // 简单的命令解析（按空格切分，注意：不处理复杂引号）
    private static List<String> parseCommand(String command) {
        return Arrays.stream(command.split(" ")).filter(s -> s.length() > 0).collect(Collectors.toList());
    }

    // 截断超长输出避免日志/CSV 过大
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated)";
    }

    // ---- 小的数据结构 ----
    private static class ProcessResult {
        int exitCode;
        String output;
        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static class SyntaxCheckResult {
        boolean syntaxOk;
        String message;
        SyntaxCheckResult(boolean ok, String msg) {
            this.syntaxOk = ok;
            this.message = msg;
        }
    }

    private static class CheckResult {
        String file;
        String status; // "ok" / "error"
        String message;
        CheckResult(String file, String status, String message) {
            this.file = file;
            this.status = status;
            this.message = message;
        }
    }
}
