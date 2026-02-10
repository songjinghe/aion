#!/bin/bash

# start aion server
function start_db_server() {
    cd /db/bin/aion/community/temporal-procs
    mvn -B --offline exec:java -Dexec.mainClass=org.neo4j.temporalprocs.Main
}

# 安全退出java进程的函数
stop() {
    # 定义重试次数和等待时间
    local max_interrupt_attempts=3
    local wait_seconds=7
    local attempt=0
    local process_pid

    # 循环发送INTERRUPT信号，直到达到最大重试次数
    while [ $attempt -lt $max_interrupt_attempts ]; do
        # 查找相关进程（排除grep自身），获取PID
        process_pid=$(ps aux | grep -v grep | grep -i java | awk '{print $2}')
        
        if [ -z "$process_pid" ]; then
            echo "[$(date +%Y-%m-%d\ %H:%M:%S)] 未检测到java进程，退出循环"
            return 0
        fi

        # 发送INTERRUPT信号（等同于SIGINT，信号2）
        echo "[$(date +%Y-%m-%d\ %H:%M:%S)] 第 $((attempt+1)) 次尝试：向java进程(PID: $process_pid)发送INTERRUPT信号"
        kill -INT "$process_pid" 2>/dev/null

        # 等待指定时间后再次检查
        echo "[$(date +%Y-%m-%d\ %H:%M:%S)] 等待 $wait_seconds 秒后检查进程状态..."
        sleep $wait_seconds

        # 检查进程是否已退出
        if ! ps -p "$process_pid" >/dev/null 2>&1; then
            echo "[$(date +%Y-%m-%d\ %H:%M:%S)] java进程(PID: $process_pid)已退出"
            return 0
        fi

        attempt=$((attempt + 1))
    done

    # 如果重试4次INTERRUPT后进程仍存在，发送TERM信号（信号15）
    process_pid=$(ps aux | grep -v grep | grep -i java | awk '{print $2}')
    if [ -n "$process_pid" ]; then
        echo "[$(date +%Y-%m-%d\ %H:%M:%S)] INTERRUPT信号重试$max_interrupt_attempts次无效，发送TERM信号"
        kill -TERM "$process_pid" 2>/dev/null
        
        # 最后检查一次进程状态
        sleep $wait_seconds
        if ps -p "$process_pid" >/dev/null 2>&1; then
            echo "[$(date +%Y-%m-%d\ %H:%M:%S)] 警告：TERM信号发送7s后java进程仍未退出"
            kill -KILL "$process_pid" 2>/dev/null
            return 1
        else
            echo "[$(date +%Y-%m-%d\ %H:%M:%S)] java进程已通过TERM信号终止"
            return 0
        fi
    fi
    return 0
}


# 处理命令行参数
main() {
    # 如果没有参数，执行默认函数
    if [ $# -eq 0 ]; then
        start_db_server
    else
        # 遍历所有参数并执行对应的函数
        for arg in "$@"; do
            if [ "$(type -t "$arg")" = "function" ]; then
                $arg  # 调用对应函数
            else
                echo "Warning: Bash function '$arg' undefined, use args as cmd."
                $arg
#                exit 1
            fi
        done
    fi
}

# 调用主函数并传递所有参数
main "$@"
