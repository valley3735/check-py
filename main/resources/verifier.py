import os
import sys
import subprocess
import argparse
import json
import shutil
import time
import ast # 【新增】用于快速语法检查

# 依赖管理文件优先级
DEP_FILES = ["requirements.txt", "pyproject.toml"]
TIMEOUT_SECONDS = 15 # 脚本最大执行时间

def find_dep_file(project_dir):
    """在项目目录中查找依赖文件。"""
    for filename in DEP_FILES:
        path = os.path.join(project_dir, filename)
        if os.path.exists(path):
            return path
    return None

def get_venv_python_executable(venv_path):
    """获取 Windows 虚拟环境中的 Python 解释器路径。"""
    return os.path.join(venv_path, 'Scripts', 'python.exe')

def install_dependencies(project_dir, venv_path):
    """在指定的 venv_path 中创建虚拟环境并安装依赖。"""
    dep_file = find_dep_file(project_dir)
    python_executable = get_venv_python_executable(venv_path)

    # 1. 虚拟环境 (VENV) 的创建
    try:
        print(f"[VENV_SETUP] 创建虚拟环境到: {venv_path}", file=sys.stderr)
        subprocess.check_call([sys.executable, "-m", "venv", os.path.abspath(venv_path)],
                              stdout=sys.stderr, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError:
        print(f"[ERROR] 无法创建虚拟环境: {venv_path}", file=sys.stderr)
        return False

    if not os.path.exists(python_executable):
        print(f"[ERROR] 找不到虚拟环境中的Python解释器: {python_executable}", file=sys.stderr)
        return False

    # 2. 核心依赖安装（不再进行额外的 polars 补充安装，依赖问题应由项目本身解决）
    if dep_file:
        dep_filename = os.path.basename(dep_file)
        print(f"[DEP_INSTALL] 检测到依赖文件 '{dep_filename}'. 正在安装...", file=sys.stderr)
        try:
            cmd = [python_executable, "-m", "pip", "install", "--upgrade", "pip"]
            subprocess.check_call(cmd, stdout=sys.stderr, stderr=subprocess.STDOUT)

            cmd = [python_executable, "-m", "pip", "install"]

            if dep_filename == "requirements.txt":
                cmd.extend(["-r", dep_file])
            elif dep_filename == "pyproject.toml":
                cmd.append(".")

            subprocess.check_call(cmd, cwd=project_dir, stdout=sys.stderr, stderr=subprocess.STDOUT)
            print("[DEP_INSTALL] 依赖安装成功。", file=sys.stderr)
            return True
        except subprocess.CalledProcessError as e:
            # 依赖安装失败是致命错误
            print(f"[ERROR] 依赖安装失败 (文件: {dep_filename}, 退出码: {e.returncode})。", file=sys.stderr)
            return False
    else:
        print("[DEP_INSTALL] 未检测到依赖文件。跳过核心安装。", file=sys.stderr)

    return True

def check_syntax(file_path):
    """
    【新增】第一阶段检查：纯粹的语法检查，不受依赖链和外部环境影响。
    """
    try:
        # 使用 UTF-8 编码读取文件内容
        with open(file_path, "r", encoding="utf-8") as f:
            # 尝试解析 Python 文件内容
            ast.parse(f.read())
        return None # 语法正确
    except SyntaxError as e:
        # 返回详细的语法错误信息
        return f"SyntaxError: {e.msg} at line {e.lineno}"
    except Exception as e:
        # 捕获文件读取或其他非语法错误
        return f"FileReadError: cannot read file ({e})"


def run_import_test(python_executable, project_dir, file_path):
    """
    第二阶段检查：尝试导入模块，检查运行时和依赖错误。
    """
    # 转换为模块名
    try:
        relative_path = os.path.relpath(file_path, project_dir)
        module_name = relative_path.replace(os.path.sep, '.').replace('.py', '')

        if module_name.endswith(".__init__"):
            module_name = module_name.replace(".__init__", "")

        if not module_name:
             return "FAILURE", "无法将文件转换为有效的模块名进行导入"

    except ValueError:
        return "FAILURE", "模块路径转换失败"

    run_cmd = [python_executable, "-c", f"import {module_name}"]
    env = os.environ.copy()
    env['PYTHONPATH'] = project_dir + os.pathsep + env.get('PYTHONPATH', '')

    try:
        result = subprocess.run(
            run_cmd,
            env=env,
            timeout=TIMEOUT_SECONDS,
            capture_output=True,
            text=True,
            encoding="utf-8"
        )

        if result.returncode != 0:
            error_output = (result.stdout + result.stderr).strip()
            # 【关键区分】如果导入失败且 Traceback 包含 ModuleNotFoundError，说明是依赖或环境问题。
            if "ModuleNotFoundError" in error_output:
                 return "DEPENDENCY_FAILURE", error_output
            else:
                 # 其他运行时错误，如 NameError, TypeError 等，可能由内部逻辑错误引起
                 return "RUNTIME_FAILURE", error_output

        # 导入成功
        return "SUCCESS", None
    except subprocess.TimeoutExpired:
        return "FAILURE", f"脚本执行超时 (超过 {TIMEOUT_SECONDS} 秒)"
    except Exception as e:
        return "FAILURE", f"未知执行错误: {str(e)}"

def main():
    parser = argparse.ArgumentParser(description="Python项目验证脚本。")
    parser.add_argument('project_dir', type=str, help='要验证的Python项目根目录')
    parser.add_argument('venv_path', type=str, help='虚拟环境的安装路径')
    parser.add_argument('target_files', nargs='+', help='要执行测试的Python文件列表 (相对于 project_dir)')

    args = parser.parse_args()
    project_dir = os.path.abspath(args.project_dir)
    venv_path = os.path.abspath(args.venv_path)

    # 1. 依赖安装
    if not install_dependencies(project_dir, venv_path):
        sys.exit(1)

    python_executable = get_venv_python_executable(venv_path)
    results = []

    print(f"\n[INFO] 共 {len(args.target_files)} 个文件需要测试 (两阶段检查)...", file=sys.stderr)

    for relative_path in args.target_files:
        full_path = os.path.join(project_dir, relative_path)
        file_name = os.path.basename(full_path)

        # 阶段 1: 语法检查 (不受依赖影响，可定位人为的 SyntaxError)
        syn_error = check_syntax(full_path)
        if syn_error:
            result = {"file": file_name, "status": "SYNTAX_ERROR", "error": syn_error}
            print(f"[STATUS] ❌ 语法错误: {file_name} ({syn_error.splitlines()[0]})", file=sys.stderr)
        else:
            # 阶段 2: 导入检查 (检查依赖和运行时初始化)
            status, error = run_import_test(python_executable, project_dir, full_path)
            result = {"file": file_name, "status": status, "error": error}

            if status == 'SUCCESS':
                print(f"[STATUS] ✅ 成功: {file_name}", file=sys.stderr)
            elif status == 'DEPENDENCY_FAILURE':
                # 依赖失败，可能是 polars 问题
                print(f"[STATUS] ⚠️ 依赖失败: {file_name} ({error.splitlines()[-1]})", file=sys.stderr)
            elif status == 'RUNTIME_FAILURE':
                # 其他运行时错误
                print(f"[STATUS] ❌ 运行时错误: {file_name} ({error.splitlines()[-1]})", file=sys.stderr)
            else:
                 # 其他失败（如超时）
                 print(f"[STATUS] ❌ 失败: {file_name} ({error.splitlines()[0]})", file=sys.stderr)

        results.append(result)

    # 3. 最终结果以JSON格式输出到stdout
    print(json.dumps(results))

    # 4. 清理
    try:
        shutil.rmtree(venv_path)
        print(f"[CLEANUP] 临时虚拟环境 {venv_path} 清理成功。", file=sys.stderr)
    except Exception as e:
        print(f"[CLEANUP_WARN] 无法删除临时虚拟环境 {venv_path}。错误: {e}", file=sys.stderr)

    sys.exit(0)

if __name__ == "__main__":
    main()