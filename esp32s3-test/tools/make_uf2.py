"""Convert firmware.bin to UF2 and copy to H:\ drive"""
import struct
import os
import sys
import shutil

def bin_to_uf2(bin_path, uf2_path, family_id=0xc47e5767, addr=0x00000000):
    """Convert a binary file to UF2 format"""
    with open(bin_path, 'rb') as f:
        data = f.read()

    print(f'Input: {bin_path} ({len(data)} bytes)')

    UF2_MAGIC_START0 = 0x0A324655
    UF2_MAGIC_START1 = 0x9E5D5157
    UF2_MAGIC_END = 0x0AB16F30
    FLAG = 0x00001000  # family ID present

    blocks = []
    offset = 0
    block_num = 0

    while offset < len(data):
        chunk = data[offset:offset + 476]
        chunk_size = len(chunk)
        padded = chunk + b'\x00' * (476 - chunk_size)

        hdr = struct.pack('<IIIIIIII',
            UF2_MAGIC_START0,
            UF2_MAGIC_START1,
            FLAG,
            addr + offset,
            chunk_size,
            block_num,
            0,  # total blocks placeholder
            family_id
        )
        block = hdr + padded + struct.pack('<I', UF2_MAGIC_END)
        blocks.append(block)
        offset += 476
        block_num += 1

    # Update total blocks
    num_blocks = len(blocks)
    for i in range(num_blocks):
        blocks[i] = blocks[i][:20] + struct.pack('<I', num_blocks) + blocks[i][24:]

    with open(uf2_path, 'wb') as f:
        for b in blocks:
            f.write(b)

    print(f'Output: {uf2_path} ({os.path.getsize(uf2_path)} bytes, {num_blocks} blocks)')
    return True


if __name__ == '__main__':
    # Paths
    base = r'd:\Documents\PlatformIO\Tdriver\esp32s3-test\.pio\build\esp32-s3-devkitc-1'
    bin_path = os.path.join(base, 'firmware.bin')
    uf2_path = os.path.join(base, 'firmware.uf2')
    dst_path = r'H:\CURRENT.UF2'

    # Convert
    if not os.path.exists(bin_path):
        print(f'ERROR: {bin_path} not found!')
        sys.exit(1)

    bin_to_uf2(bin_path, uf2_path)

    # Copy to H:\
    print(f'\nCopying to {dst_path}...')
    try:
        if os.path.exists(dst_path):
            os.remove(dst_path)
            print('Removed old CURRENT.UF2')
        
        shutil.copy2(uf2_path, dst_path)
        print(f'Copied successfully! Size: {os.path.getsize(dst_path)} bytes')
    except Exception as e:
        print(f'Copy failed: {e}')
        
        # Try with subprocess
        import subprocess
        cmd = ['powershell', '-Command', 
               f'Remove-Item "{dst_path}" -Force -ErrorAction SilentlyContinue; ' +
               f'Start-Sleep 1; ' +
               f'Copy-Item "{uf2_path}" "{dst_path}" -Force']
        result = subprocess.run(cmd, capture_output=True, text=True, shell=True)
        if os.path.exists(dst_path):
            print(f'PowerShell copy succeeded! Size: {os.path.getsize(dst_path)} bytes')
        else:
            print(f'PowerShell copy also failed.')
            print(f'stdout: {result.stdout}')
            print(f'stderr: {result.stderr}')
