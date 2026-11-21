#!/usr/bin/env python3
"""Native sync/process smoke test. Requires a fresh, disposable API 35 emulator."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.ayaanshaikh.offline"
RUNNER = PACKAGE + ".test/" + PACKAGE + ".NativeSyncSmoke"
ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit emulator serial; never auto-select a device")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--evidence", type=pathlib.Path, required=True, help="New directory for logs/screenshots")
    parser.add_argument("--disposable-emulator", action="store_true", required=True,
                        help="Acknowledge that this fresh emulator is exclusively for test fixtures")
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]
    log = (args.evidence / "commands.log").open("w")

    def run(*command, timeout=90):
        result = subprocess.run(adb + list(command), capture_output=True, text=True, timeout=timeout)
        log.write("$ " + " ".join(command) + "\n" + result.stdout + result.stderr + "\n")
        log.flush()
        if result.returncode:
            raise RuntimeError(f"Command failed: {command}: {result.stdout}{result.stderr}")
        return result.stdout

    def shell(*command):
        return run("shell", *command)

    def wait_for(description, predicate, seconds=180):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if predicate():
                return
            time.sleep(1)
        raise RuntimeError("Timed out: " + description)

    def stage(name):
        output = run("shell", "am", "instrument", "-w", "-r", "-e", "stage", name, RUNNER)
        (args.evidence / (name + ".txt")).write_text(output)
        if "PASS " + name not in output or "FAIL " in output:
            raise RuntimeError("Instrumentation failed: " + output)

    processes = []

    def wait_marker(process, path, marker):
        def ready():
            text = path.read_text()
            if "FAIL " in text or process.poll() is not None:
                raise RuntimeError("Stage ended before marker: " + text)
            return marker in text
        wait_for(marker, ready)

    def start_stage(name, marker):
        path = args.evidence / (name + ".txt")
        output = path.open("w")
        process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "stage", name, RUNNER],
                                   stdout=output, stderr=subprocess.STDOUT)
        processes.append((process, output))
        wait_marker(process, path, marker)
        return process, path

    # Refuse existing installations rather than clearing or overwriting anyone's app data.
    if not args.serial.startswith("emulator-") or shell("getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Requires an explicitly selected emulator")
    if shell("getprop", "ro.build.version.sdk").strip() != "35":
        raise RuntimeError("This smoke procedure currently targets Android API 35")
    if shell("getprop", "sys.boot_completed").strip() != "1":
        raise RuntimeError("Wait for Android to finish booting before running this procedure")
    if shell("am", "get-current-user").strip() != "0":
        raise RuntimeError("Requires disposable emulator owner user 0")
    installed = shell("pm", "list", "packages", PACKAGE).splitlines()
    if {"package:" + PACKAGE, "package:" + PACKAGE + ".test"}.intersection(installed):
        raise RuntimeError("App already installed. Use a fresh emulator; no data will be cleared")
    wifi = shell("settings", "get", "global", "wifi_on").strip()
    portal = shell("settings", "get", "global", "captive_portal_mode").strip()
    try:
        run("install", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
        run("install", "-t", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
        shell("svc", "wifi", "disable")
        wait_for("Wi-Fi disabled", lambda: shell("settings", "get", "global", "wifi_on").strip() == "0")
        process, _ = start_stage("checkpoint", "CHECKPOINT_READY")
        pid = shell("pidof", PACKAGE).strip()
        if not re.fullmatch(r"\d+", pid):
            raise RuntimeError("Expected exactly one fixture process, got: " + pid)
        # SIGKILL, not force-stop: retain Android's scheduled work and leave SQLite open at termination.
        shell("run-as", PACKAGE, "kill", "-9", pid)
        process.wait(timeout=30)
        stage("inspect")
        process, block_output = start_stage("block", "BLOCK_READY")
        # Make the queued job eligible before cancellation. An uncancelled worker would then
        # consume the cursor when released, so the checkpoint assertion detects that regression.
        shell("settings", "put", "global", "captive_portal_mode", "0")
        shell("svc", "wifi", "enable")
        shell("cmd", "wifi", "connect-network", "AndroidWifi", "open")
        wait_marker(process, block_output, "WIFI_READY")
        (args.evidence / "eligible-network.txt").write_text(shell("dumpsys", "connectivity"))
        started = shell("cmd", "jobscheduler", "run", "-f", PACKAGE, "1102")
        if "Running job" not in started:
            raise RuntimeError("Native job did not start: " + started)
        wait_for("job active", lambda: "active" in shell("cmd", "jobscheduler", "get-job-state", PACKAGE, "1102").split())
        (args.evidence / "job-active.txt").write_text(shell("dumpsys", "jobscheduler"))
        stopped = shell("cmd", "jobscheduler", "timeout", PACKAGE, "1102")
        if not any(message in stopped for message in ("Timing out", "Stopping job:")):
            raise RuntimeError("Native job was not stopped: " + stopped)
        wait_for("job stopped", lambda: "active" not in shell("cmd", "jobscheduler", "get-job-state", PACKAGE, "1102").split())
        shell("run-as", PACKAGE, "touch", "files/native-smoke-release")
        process.wait(timeout=60)
        text = block_output.read_text()
        if "PASS block" not in text or "FAIL " in text:
            raise RuntimeError("Cancelled worker lost checkpoint: " + text)
        # Resume the cancelled job explicitly, bypassing its normal retry delay.
        shell("cmd", "jobscheduler", "run", "-f", PACKAGE, "1102")
        wait_for("native resume acknowledgement", lambda: "forceInFlight" not in shell(
            "run-as", PACKAGE, "cat", "shared_prefs/sync.xml"))
        (args.evidence / "jobs-after-resume.txt").write_text(shell("dumpsys", "jobscheduler"))
        stage("resumed")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")

        def ui(name):
            shell("uiautomator", "dump", "/data/local/tmp/offline-native-window.xml")
            text = shell("cat", "/data/local/tmp/offline-native-window.xml")
            (args.evidence / (name + ".xml")).write_text(text)
            screenshot = subprocess.run(adb + ["exec-out", "screencap", "-p"], check=True,
                                        capture_output=True, timeout=60).stdout
            (args.evidence / (name + ".png")).write_bytes(screenshot)
            return ET.fromstring(text)

        view = ui("sources")
        # Software emulation can leave a System UI ANR dialog from boot. Preserve it,
        # choose Wait once, and recheck. Never dismiss an Offline ANR as an environment issue.
        if any(n.get("text") == "System UI isn't responding" for n in view.iter("node")):
            wait_button = next(n for n in view.iter("node") if n.get("resource-id") == "android:id/aerr_wait")
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", wait_button.get("bounds")))
            shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            (args.evidence / "system-ui-anr.txt").write_text(
                "System UI ANR observed; selected Wait once. Original sources screenshot/XML preserved.\n")
            view = ui("sources-after-system-ui-wait")
        texts = [n.get("text", "") for n in view.iter("node")]
        if not all(any("Native smoke " + str(i) in t for t in texts) for i in range(3)):
            raise RuntimeError("Sources did not render all fixture playlists")
        log_tab = next(n for n in view.iter("node") if n.get("text") == "03 / logs")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", log_tab.get("bounds")))
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        logs = ui("log")
        if not any("Connect Spotify" in n.get("text", "") for n in logs.iter("node")):
            raise RuntimeError("Log tab did not render metadata diagnostics")
        crashes = shell("logcat", "-d", "-b", "crash")
        (args.evidence / "crash-buffer.txt").write_text(crashes)
        if "Process: " + PACKAGE + "," in crashes:
            raise RuntimeError("Offline crash recorded; inspect crash-buffer.txt")
        (args.evidence / "result.txt").write_text(
            "PASS: durable checkpoint after SIGKILL; native queued-job timeout/cancellation; "
            "native job resume without replay; Sources and Log UI interaction.\n"
            "Fixtures use disconnected Spotify errors before HTTP. Not a socket cancellation, "
            "power-loss, apply() crash-window, live provider, or audio-decoding test.\n")
        print("PASS; evidence: " + str(args.evidence))
    finally:
        for process, output in processes:
            if process.poll() is None:
                process.terminate()
            output.close()
        shell("svc", "wifi", "enable" if wifi == "1" else "disable")
        if portal == "null":
            shell("settings", "delete", "global", "captive_portal_mode")
        else:
            shell("settings", "put", "global", "captive_portal_mode", portal)
        log.close()


if __name__ == "__main__":
    main()
