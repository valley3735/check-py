# -*- coding: utf-8 -*-
import json
import sys
import ast
import subprocess

def check_syntax(path):
    """ 语法检查（不执行代码） """
    try:
        # 使用 UTF-8 编码读取文件内容
        with open(path, "r", encoding="utf-8") as f:
            # 尝试解析 Python 文件内容
            ast.parse(f.read())
        return None
    except SyntaxError as e:
        # 返回详细的语法错误信息
        return f"SyntaxError: {e.msg} at line {e.lineno}"
    except Exception as e:
        # 捕获文件读取或其他非语法错误
        return f"SyntaxError: cannot read file ({e})"

def check_pyflakes(path):
    """ 使用 pyflakes 做静态分析 (通过 Python -m 模式调用) """
    try:
        # 【关键修改】使用 sys.executable -m pyflakes 来运行，避免 Windows 权限问题 (WinError 5)
        result = subprocess.run(
            [sys.executable, "-m", "pyflakes", path],
            capture_output=True,
            text=True,
            # 确保使用 UTF-8 编码捕获输出，与 Java 端保持一致
            encoding="utf-8",
        )

        output = result.stdout.strip()

        if result.returncode != 0 and not output:
             # 如果返回非零代码但没有输出，可能是 pyflakes 执行失败
             return f"Pyflakes execution failed with return code {result.returncode}"

        if output:
            # 如果 pyflakes 有输出（即发现问题），则返回输出
            return output
        return None
    except Exception as e:
        # 捕获可能发生的底层错误，如 WinError 5
        error_msg = str(e)
        if "WinError 5" in error_msg:
             # 返回明确的错误信息
             return "pyflakes_runtime_error: Access Denied (WinError 5). Check file execution rights."
        return f"pyflakes_runtime_error: {error_msg}"

def check_file(path):
    # 档 1: 语法检查
    syn = check_syntax(path)
    if syn:
        return {"status": "syntax_error", "message": syn}

    # 档 2: 静态语义检查
    semantic = check_pyflakes(path)

    # 检查运行时错误
    if semantic and semantic.startswith("pyflakes_runtime_error"):
        return {"status": "error", "message": semantic}

    # 检查 pyflakes 发现的语义错误
    if semantic:
        return {"status": "semantic_error", "message": semantic}

    return {"status": "ok"}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        # 只需要文件路径一个参数
        print(json.dumps({"status": "error", "message": "Missing file path argument"}, ensure_ascii=False))
        sys.exit(1)

    path = sys.argv[1] # 待检查的 Python 文件路径

    # 最终结果以 JSON 格式输出到 stdout
    print(json.dumps(check_file(path), ensure_ascii=False))