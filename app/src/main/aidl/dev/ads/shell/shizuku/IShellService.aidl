package dev.ads.shell.shizuku;

// Shizuku UserService 接口：服务进程运行在 adb shell (uid 2000) 下，
// 使本应用无 root 时也能执行 pm / am / settings / dumpsys / logcat 等特权命令。
interface IShellService {
    String exec(String command);

    void exit();
}
