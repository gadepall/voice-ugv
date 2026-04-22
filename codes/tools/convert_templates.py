"""
convert_templates.py  —  run once on your PC
Reads esp32_side/include/templates.h and writes binary .bin files
into a folder you copy to  app/src/main/assets/templates/

Usage:
    python convert_templates.py --templates ../../esp32_side/include/templates.h \
                                --out        ../../app/src/main/assets/templates
"""

import re, struct, os, argparse

COMMANDS = ["forward", "back", "left", "right", "stop"]

def parse_template(src: str, name: str):
    # extract float array
    pattern = rf"template_{name}\[\d+\]\s*=\s*\{{(.+?)\}};"
    m = re.search(pattern, src, re.DOTALL)
    if not m:
        raise ValueError(f"Could not find template_{name} in file")
    floats = [float(x.strip().rstrip('f')) for x in m.group(1).split(",") if x.strip()]

    # extract frame count
    fm = re.search(rf"TEMPLATE_{name.upper()}_FRAMES\s+(\d+)", src)
    if not fm:
        raise ValueError(f"Could not find TEMPLATE_{name.upper()}_FRAMES")
    n_frames = int(fm.group(1))
    n_coeffs = len(floats) // n_frames

    print(f"  {name:10s}  frames={n_frames}  coeffs={n_coeffs}  total={len(floats)}")
    return n_frames, n_coeffs, floats

def write_bin(path: str, n_frames: int, n_coeffs: int, floats: list):
    """
    Binary format:
      4 bytes  — int32 n_frames   (little-endian)
      4 bytes  — int32 n_coeffs
      n_frames * n_coeffs * 4 bytes — float32 row-major
    """
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(struct.pack("<ii", n_frames, n_coeffs))
        f.write(struct.pack(f"<{len(floats)}f", *floats))
    print(f"  wrote {path}  ({os.path.getsize(path)} bytes)")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--templates", default="include/templates.h")
    parser.add_argument("--out",       default="assets/templates")
    args = parser.parse_args()

    with open(args.templates, "r") as f:
        src = f.read()

    print("Parsing templates.h ...")
    for cmd in COMMANDS:
        n_frames, n_coeffs, floats = parse_template(src, cmd)
        # index 0 = the original single template from templates.h
        out_path = os.path.join(args.out, f"{cmd}_0.bin")
        write_bin(out_path, n_frames, n_coeffs, floats)

    print(f"\nDone. Copy the '{args.out}' folder to:")
    print("  app/src/main/assets/templates/")
    print("\nEach command starts with 1 template (index 0).")
    print("New recordings saved by the app are index 1..9.")

if __name__ == "__main__":
    main()
