import subprocess, sys, tempfile, os, shutil
import zipfile
from protobuf_decoder import Parser, parse_apx_manifest, parse_classpath_bin


def extract_file_7z(archive_path, file_to_extract, output_dir):
    subprocess.run(['7z', 'e', archive_path, f'-o{output_dir}', file_to_extract], check=True)

def list_files_7z(archive_path):
    result = subprocess.run(['7z', 'l', '-ba', archive_path], stdout=subprocess.PIPE, check=True)
    return result.stdout.decode("utf-8")

def fix_extract_path(path):
    if path.startswith("/"):
        return path[1:]
    return path

if __name__ == '__main__':
    system_img_path = sys.argv[1]

    with tempfile.TemporaryDirectory() as tempdir:
        extract_file_7z(system_img_path, 'system/build.prop', tempdir)
        extract_file_7z(system_img_path, 'system/etc/classpaths/bootclasspath.pb', tempdir)
        extract_file_7z(system_img_path, 'system/etc/classpaths/systemserverclasspath.pb', tempdir)

        file_list = []
        for line in list_files_7z(system_img_path).splitlines():
            line_split = line.split()
            if line_split[2][0] != 'D':
                file_list.append(line_split[-1])

        fingerprint = None
        security_patch = None
        product_name = None
        brand = None
        release = None
        build_id = None

        with open(os.path.join(tempdir, "build.prop"), "r", encoding="utf-8") as f:
            for line in f:
                if line.startswith("ro.system.build.fingerprint="):
                    fingerprint = line[len("ro.system.build.fingerprint="):].strip()
                elif line.startswith("ro.build.version.security_patch="):
                    security_patch = line[len("ro.build.version.security_patch="):].strip()
                elif line.startswith("ro.product.system.name="):
                    product_name = line[len("ro.product.system.name="):].strip()
                elif line.startswith("ro.product.system.brand="):
                    brand = line[len("ro.product.system.brand="):].strip()
                elif line.startswith("ro.build.version.release="):
                    release = line[len("ro.build.version.release="):].strip()
                elif line.startswith("ro.build.id="):
                    build_id = line[len("ro.build.id="):].strip()
        
        out_path = os.path.join("base_rom", brand, product_name, build_id)
        print(out_path)
        shutil.rmtree(out_path, ignore_errors=True)
        os.makedirs(out_path, exist_ok=True)

        with open(os.path.join(out_path, "fingerprint.txt"), "w") as f:
            f.write(fingerprint)
        
        with open(os.path.join(out_path, "security_patch.txt"), "w") as f:
            f.write(security_patch)

        with open(os.path.join(out_path, "product.txt"), "w") as f:
            f.write(product_name)

        with open(os.path.join(out_path, "brand.txt"), "w") as f:
            f.write(brand)

        with open(os.path.join(out_path, "release.txt"), "w") as f:
            f.write(release)

        shutil.copyfile(os.path.join(tempdir, "build.prop"), os.path.join(out_path, "build.prop"))


        extract_file_7z(system_img_path, "system/etc/selinux/plat_sepolicy.cil", tempdir)
        shutil.move(os.path.join(tempdir, "plat_sepolicy.cil"), os.path.join(out_path, "plat_sepolicy.cil"))
        extract_file_7z(system_img_path, "system/etc/selinux/plat_service_contexts", tempdir)
        shutil.move(os.path.join(tempdir, "plat_service_contexts"), os.path.join(out_path, "plat_service_contexts"))

        if "system/system_ext/etc/selinux/system_ext_sepolicy.cil" in file_list:
            extract_file_7z(system_img_path, "system/system_ext/etc/selinux/system_ext_sepolicy.cil", tempdir)
            shutil.move(os.path.join(tempdir, "system_ext_sepolicy.cil"), os.path.join(out_path, "system_ext_sepolicy.cil"))
        else: # write empty file
            with open(os.path.join(out_path, "system_ext_sepolicy.cil"), "w") as f:
                pass
        
        if "system/system_ext/etc/selinux/system_ext_service_contexts" in file_list:
            extract_file_7z(system_img_path, "system/system_ext/etc/selinux/system_ext_service_contexts", tempdir)
            shutil.move(os.path.join(tempdir, "system_ext_service_contexts"), os.path.join(out_path, "system_ext_service_contexts"))
        else: # write empty file
            with open(os.path.join(out_path, "system_ext_service_contexts"), "w") as f:
                pass


        with open(os.path.join(tempdir, "bootclasspath.pb"), "rb") as f:
            bootcp = parse_classpath_bin(f.read())
        
        with open(os.path.join(tempdir, "systemserverclasspath.pb"), "rb") as f:
            syscp = parse_classpath_bin(f.read())
        
        bootcp_path = os.path.join(out_path, "bootcp")
        os.makedirs(bootcp_path, exist_ok=True)
        for path in bootcp:
            if path.startswith("/apex/"):
                continue
            local_path = os.path.join(bootcp_path, path[1:])
            local_dir = os.path.dirname(local_path)
            os.makedirs(local_dir, exist_ok=True)

            filename = os.path.basename(local_path)
            extract_file_7z(system_img_path, fix_extract_path(path), tempdir)
            shutil.move(os.path.join(tempdir, filename), local_path)
        
        syscp_path = os.path.join(out_path, "systemservercp")
        os.makedirs(syscp_path, exist_ok=True)
        for path in syscp:
            if path.startswith("/apex/"):
                continue
            local_path = os.path.join(syscp_path, path[1:])
            local_dir = os.path.dirname(local_path)
            os.makedirs(local_dir, exist_ok=True)

            filename = os.path.basename(local_path)
            extract_file_7z(system_img_path, fix_extract_path(path), tempdir)
            shutil.move(os.path.join(tempdir, filename), local_path)
        
        art_bootcp: list[str] = None
        art_syscp: list[str] = None
        apex_files = filter(lambda x: x.startswith("system/apex/"), file_list)
        for item in apex_files:
            file_name = os.path.basename(item)
            extract_file_7z(system_img_path, item, tempdir)
            
            try:
                with zipfile.ZipFile(os.path.join(tempdir, file_name), "r") as z:
                    manifest_data = z.read("apex_manifest.pb")
                    apex_name = parse_apx_manifest(manifest_data)[0]["data"]
                    names = z.namelist()

                    payload_path = os.path.join(tempdir, "apex_payload.img")

                    if "original_apex" in names:
                        print("Found original_apex in", file_name)
                        with zipfile.ZipFile(z.open("original_apex"), "r") as orig_z:
                            orig_z.extract("apex_payload.img", tempdir)
                    elif "apex_payload.img" in names:
                        z.extract("apex_payload.img", tempdir)
                    else:
                        raise Exception(f"APEX payload not found in {file_name}")

                    ext_path = os.path.join(tempdir, apex_name)
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
                print(f"Error processing {file_name}: {e}")
        
        bootcp = art_bootcp + bootcp
        syscp = art_syscp + syscp

        for path in bootcp:
            if not path.startswith("/apex/"):
                continue
            src_path = os.path.join(tempdir, path[len("/apex/") :])
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
            src_path = os.path.join(tempdir, path[len("/apex/") :])
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

