#!/usr/bin/env python3
"""Exercise Android TLS body reads and actual Wi-Fi eligibility changes on a disposable API 35 VM."""
import argparse
import pathlib
import subprocess
import time

PACKAGE = "com.ayaanshaikh.offline"
ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--adb", default="adb")
    p.add_argument("--evidence", type=pathlib.Path, required=True)
    p.add_argument("--disposable-emulator", action="store_true", required=True)
    args = p.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]
    log = (args.evidence / "commands.log").open("w")

    def run(*command):
        r = subprocess.run(adb + list(command), capture_output=True, text=True, timeout=90)
        log.write("$ " + " ".join(command) + "\n" + r.stdout + r.stderr + "\n")
        log.flush()
        if r.returncode:
            raise RuntimeError(f"Command failed: {command}: {r.stdout}{r.stderr}")
        return r.stdout

    def shell(*command):
        return run("shell", *command)

    if not args.serial.startswith("emulator-") or shell("getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("An explicitly selected disposable emulator is required")
    if shell("getprop", "ro.build.version.sdk").strip() != "35" or shell("getprop", "sys.boot_completed").strip() != "1":
        raise RuntimeError("Wait for an API 35 emulator to finish booting")
    if shell("am", "get-current-user").strip() != "0":
        raise RuntimeError("Requires disposable emulator user 0")
    wifi = shell("settings", "get", "global", "wifi_on").strip()
    portal = shell("settings", "get", "global", "captive_portal_mode").strip()
    process = None
    output = (args.evidence / "transport.txt").open("w")
    try:
        # Update in place; never clear/uninstall an existing app or modify its library.
        run("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
        run("install", "-r", "-t", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
        shell("settings", "put", "global", "captive_portal_mode", "0")
        shell("svc", "wifi", "enable")
        shell("cmd", "wifi", "connect-network", "AndroidWifi", "open")
        runner = PACKAGE + ".test/" + PACKAGE + ".NativeSyncSmoke"
        process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "stage", "transport", runner],
                                   stdout=output, stderr=subprocess.STDOUT)

        def marker(name):
            end = time.monotonic() + 240
            while time.monotonic() < end:
                text = (args.evidence / "transport.txt").read_text()
                if "FAIL " in text:
                    raise RuntimeError(text)
                if name in text:
                    return
                if process.poll() is not None:
                    raise RuntimeError("Instrumentation ended before " + name + ": " + text)
                time.sleep(1)
            raise RuntimeError("Timed out waiting for " + name)

        marker("BODY_WAITING_FOR_WIFI_LOSS")
        shell("svc", "wifi", "disable")
        marker("WIFI_LOSS_PASS_RECONNECT")
        shell("svc", "wifi", "enable")
        shell("cmd", "wifi", "connect-network", "AndroidWifi", "open")
        marker("TRANSPORT_COMPLETE")
        process.wait(timeout=30)
        text = (args.evidence / "transport.txt").read_text()
        if "PASS transport" not in text or "FAIL " in text:
            raise RuntimeError(text)
        crashes = shell("logcat", "-d", "-b", "crash")
        (args.evidence / "crash-buffer.txt").write_text(crashes)
        if "Process: " + PACKAGE + "," in crashes:
            raise RuntimeError("Offline crash recorded; inspect crash-buffer.txt")
        (args.evidence / "result.txt").write_text(
            "PASS: native TLS save, default trust rejection, hostname rejection, Wi-Fi eligibility loss/cleanup, "
            "reconnection/retry, interrupted blocked reader/cleanup.\n"
            "TLS travels over loopback, not the Wi-Fi interface. No native JobService transfer, provider access, "
            "decoding, immediate production socket cancellation, or power-loss claim.\n")
        print("PASS; evidence: " + str(args.evidence))
    finally:
        if process is not None and process.poll() is None:
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
