import os

log_path = r"C:\Users\sixpi\AndroidStudioProjects\UberEatsOverlay\uber_debug_logs.txt"

if os.path.exists(log_path):
    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        lines = f.readlines()

    # Filter out lines shorter than 35 characters
    filtered_lines = [line for line in lines if len(line) >= 35]

    with open(log_path, 'w', encoding='utf-8') as f:
        f.writelines(filtered_lines)

    print(f"Cleanup complete. Removed {len(lines) - len(filtered_lines)} short lines.")
else:
    print(f"Error: Could not find log file at {log_path}")