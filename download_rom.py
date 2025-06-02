from itertools import chain
import requests, os, sys, shutil, binascii, zipfile, subprocess
from protobuf_decoder import Parser, parse_apx_manifest, parse_classpath_bin


def download_file(url, filename):
    if os.path.exists(filename):
        print(f"{filename} already exists.")
        return
    print(f"Downloading {filename} from {url}")

    temp_filename = filename + ".part"
    with requests.get(url, stream=True) as r:
        r.raise_for_status()
        total_size = int(r.headers.get("content-length", 0))
        downloaded_size = 0

        with open(temp_filename, "wb") as f:
            for chunk in r.iter_content(chunk_size=8192):
                f.write(chunk)
                downloaded_size += len(chunk)
                print(f"\r{downloaded_size}/{total_size} bytes downloaded", end="")
        print()
    os.rename(temp_filename, filename)


def get_json(url):
    return requests.get(url).json()


def get_binary(url):
    return requests.get(url).content


def fix_download_path(path):
    if path.startswith("/system/"):
        return "/system" + path
    if path.startswith("/system_ext/"):
        return path



if __name__ == "__main__":
    oem = sys.argv[1]
    product = sys.argv[2]
    branch = sys.argv[3]

    rom_path = os.path.join("rom", oem, product, branch)
    temp_path = os.path.join(rom_path, "temp")
    os.makedirs(temp_path, exist_ok=True)

    out_path = os.path.join(rom_path, "out")
    os.makedirs(out_path, exist_ok=True)

    raw_base_url = f"https://dumps.tadiphone.dev/dumps/{oem}/{product}/-/raw/{branch}"
    api_base_url = (
        f"https://dumps.tadiphone.dev/api/v4/projects/dumps%2F{oem}%2F{product}"
    )

    download_file(
        f"{raw_base_url}/system/system/build.prop", os.path.join(out_path, "build.prop")
    )

    # SELinux rules
    download_file(
        f"{raw_base_url}/system/system/etc/selinux/plat_sepolicy.cil",
        os.path.join(out_path, "plat_sepolicy.cil"),
    )
    download_file(
        f"{raw_base_url}/system/system/etc/selinux/plat_service_contexts",
        os.path.join(out_path, "plat_service_contexts"),
    )
    download_file(
        f"{raw_base_url}/system_ext/etc/selinux/system_ext_sepolicy.cil",
        os.path.join(out_path, "system_ext_sepolicy.cil"),
    )
    download_file(
        f"{raw_base_url}/system_ext/etc/selinux/system_ext_service_contexts",
        os.path.join(out_path, "system_ext_service_contexts"),
    )

    fingerprint = None
    security_patch = None
    product_name = None
    brand = None

    with open(os.path.join(out_path, "build.prop"), "r", encoding="utf-8") as f:
        for line in f:
            if line.startswith("ro.system.build.fingerprint="):
                fingerprint = line[len("ro.system.build.fingerprint="):]
            elif line.startswith("ro.build.version.security_patch="):
                security_patch = line[len("ro.build.version.security_patch="):]
            elif line.startswith("ro.product.system.name="):
                product_name = line[len("ro.product.system.name="):]
            elif line.startswith("ro.product.system.brand="):
                brand = line[len("ro.product.system.brand="):]
    
    with open(os.path.join(out_path, "fingerprint.txt"), "w") as f:
        f.write(fingerprint)
    
    with open(os.path.join(out_path, "security_patch.txt"), "w") as f:
        f.write(security_patch)

    with open(os.path.join(out_path, "product.txt"), "w") as f:
        f.write(product_name)

    with open(os.path.join(out_path, "brand.txt"), "w") as f:
        f.write(brand)

    bootcp_bin = get_binary(
        f"{raw_base_url}/system/system/etc/classpaths/bootclasspath.pb"
    )
    bootcp = parse_classpath_bin(bootcp_bin)
    syscp_bin = get_binary(
        f"{raw_base_url}/system/system/etc/classpaths/systemserverclasspath.pb"
    )
    syscp = parse_classpath_bin(syscp_bin)

    bootcp_path = os.path.join(out_path, "bootcp")
    os.makedirs(bootcp_path, exist_ok=True)
    for path in bootcp:
        if path.startswith("/apex/"):
            continue
        local_path = os.path.join(bootcp_path, path[1:])
        local_dir = os.path.dirname(local_path)
        os.makedirs(local_dir, exist_ok=True)
        download_file(f"{raw_base_url}{fix_download_path(path)}", local_path)

    syscp_path = os.path.join(out_path, "systemservercp")
    os.makedirs(syscp_path, exist_ok=True)
    for path in syscp:
        if path.startswith("/apex/"):
            continue
        local_path = os.path.join(syscp_path, path[1:])
        local_dir = os.path.dirname(local_path)
        os.makedirs(local_dir, exist_ok=True)
        download_file(f"{raw_base_url}{fix_download_path(path)}", local_path)

    art_bootcp: list[str] = None
    art_syscp: list[str] = None

    apex_files = get_json(f"{api_base_url}/repository/tree?path=system/system/apex&ref={branch}")
    print(apex_files)
    for item in apex_files:
        name = item["name"]
        path = item["path"]

        download_file(f"{raw_base_url}/{path}", os.path.join(temp_path, name))

        try:
            with zipfile.ZipFile(os.path.join(temp_path, name), "r") as z:
                manifest_data = z.read("apex_manifest.pb")
                apex_name = parse_apx_manifest(manifest_data)[0]["data"]
                names = z.namelist()

                payload_path = os.path.join(temp_path, "apex_payload.img")

                if "original_apex" in names:
                    print("Found original_apex in", name)
                    with zipfile.ZipFile(z.open("original_apex"), "r") as orig_z:
                        orig_z.extract("apex_payload.img", temp_path)
                elif "apex_payload.img" in names:
                    z.extract("apex_payload.img", temp_path)
                else:
                    raise Exception(f"APEX payload not found in {name}")

                ext_path = os.path.join(temp_path, apex_name)
                shutil.rmtree(ext_path, ignore_errors=True)
                os.makedirs(ext_path, exist_ok=True)

                # Use 7z
                subprocess.run(["7z", "x", payload_path, f"-o{ext_path}"], check=True)

                apex_bootcp = []
                if os.path.exists(
                    os.path.join(ext_path, "etc/classpaths/bootclasspath.pb")
                ):
                    apex_bootcp_bin = open(
                        os.path.join(ext_path, "etc/classpaths/bootclasspath.pb"), "rb"
                    ).read()
                    apex_bootcp = parse_classpath_bin(apex_bootcp_bin)
                apex_syscp = []
                if os.path.exists(
                    os.path.join(ext_path, "etc/classpaths/systemserverclasspath.pb")
                ):
                    apex_syscp_bin = open(
                        os.path.join(ext_path, "etc/classpaths/systemserverclasspath.pb"),
                        "rb",
                    ).read()
                    apex_syscp = parse_classpath_bin(apex_syscp_bin)

                if apex_name == "com.android.art":
                    art_bootcp = apex_bootcp
                    art_syscp = apex_syscp
                else:
                    bootcp.extend(apex_bootcp)
                    syscp.extend(apex_syscp)
        except Exception as e:
            print(f"Error processing {name}: {e}")

    bootcp = art_bootcp + bootcp
    syscp = art_syscp + syscp

    for path in bootcp:
        if not path.startswith("/apex/"):
            continue
        src_path = os.path.join(temp_path, path[len("/apex/") :])
        if not os.path.exists(src_path):
            print(f"File {src_path} not found")
            continue
        dest_path = os.path.join(bootcp_path, path[1:])
        dest_dir = os.path.dirname(dest_path)
        os.makedirs(dest_dir, exist_ok=True)
        shutil.copyfile(src_path, dest_path)

    for path in syscp:
        if not path.startswith("/apex/"):
            continue
        src_path = os.path.join(temp_path, path[len("/apex/") :])
        if not os.path.exists(src_path):
            print(f"File {src_path} not found")
            continue
        dest_path = os.path.join(syscp_path, path[1:])
        dest_dir = os.path.dirname(dest_path)
        os.makedirs(dest_dir, exist_ok=True)
        shutil.copyfile(src_path, dest_path)

    with open(os.path.join(out_path, "bootclasspath.txt"), "w") as f:
        f.write(":".join(bootcp))

    with open(os.path.join(out_path, "systemserverclasspath.txt"), "w") as f:
        f.write(":".join(syscp))
