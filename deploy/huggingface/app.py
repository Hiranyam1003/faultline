"""Starts Faultline inside a free Hugging Face Gradio Space.

The Space only runs Python, so this downloads a Java 21 runtime and the latest Faultline
release (the jar already contains the website), then hands the process over to Java on
port 7860, the port the Space serves. To deploy a new version, publish a new GitHub release
and restart the Space.
"""
import os
import platform
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

RELEASE = os.environ.get("FAULTLINE_RELEASE", "https://github.com/Hiranyam1003/faultline/releases/latest/download/")
PORT = os.environ.get("PORT", "7860")
HOME = Path(os.environ.get("FAULTLINE_HOME", "/tmp/faultline"))


def download(url, dest):
    print(f"Downloading {url}", flush=True)
    # Adoptium rejects Python's default User-Agent, so send a plain one.
    request = urllib.request.Request(url, headers={"User-Agent": "faultline-launcher"})
    with urllib.request.urlopen(request) as response, open(dest, "wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)


def java_runtime():
    os_name = {"linux": "linux", "darwin": "mac"}[sys.platform]
    arch = {"x86_64": "x64", "amd64": "x64", "arm64": "aarch64", "aarch64": "aarch64"}[platform.machine().lower()]
    jre_dir = HOME / "jre"
    if not jre_dir.exists():
        archive = HOME / "jre.tar.gz"
        download(f"https://api.adoptium.net/v3/binary/latest/21/ga/{os_name}/{arch}/jre/hotspot/normal/eclipse", archive)
        with tarfile.open(archive) as tar:
            tar.extractall(jre_dir)
        archive.unlink()
    return next(p for p in jre_dir.rglob("bin/java") if p.is_file())


def main():
    HOME.mkdir(parents=True, exist_ok=True)
    java = java_runtime()
    jar = HOME / "faultline.jar"
    download(RELEASE + "faultline.jar", jar)
    data_zip = HOME / "faultline-data.zip"
    download(RELEASE + "faultline-data.zip", data_zip)
    with zipfile.ZipFile(data_zip) as z:
        z.extractall(HOME)
    data_zip.unlink()
    print(f"Starting Faultline on port {PORT}", flush=True)
    os.execv(str(java), [str(java), "-Xmx2g", "-jar", str(jar), f"--server.port={PORT}", f"--pwaudit.data-dir={HOME / 'data'}"])


if __name__ == "__main__":
    main()
