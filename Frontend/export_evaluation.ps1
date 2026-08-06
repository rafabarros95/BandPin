$device = "10.67.120.89:34213"
$baseDir = "D:\Project\Evulation"

New-Item -ItemType Directory -Force -Path $baseDir | Out-Null

$tempDir = Join-Path $baseDir "_temp_export"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

$eventsRaw = Join-Path $tempDir "events.csv"
$trialsRaw = Join-Path $tempDir "trials.csv"

adb -s $device shell run-as com.android.bandpinwatch cat files/bandpin_study/events.csv | Out-File -Encoding utf8 $eventsRaw
adb -s $device shell run-as com.android.bandpinwatch cat files/bandpin_study/trials.csv | Out-File -Encoding utf8 $trialsRaw

$pythonCode = @"
import csv, os, re, shutil
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter

base_dir = r"$baseDir"
events_path = r"$eventsRaw"
trials_path = r"$trialsRaw"

events_headers = ["participantId","trialNumber","timestamp","eventType","strip","zone","digit","position","boardTimeMs"]
trials_headers = ["participantId","trialNumber","targetPin","enteredPin","correct","selectionErrorCount","neighborErrorCount","entryTimeMs","completionTimeMs","numSelects","numDeletes","numTicks"]

SESSION_START = "TRIAL_START"
SESSION_END = {"TRIAL_END_SUCCESS", "TRIAL_CANCELLED"}

def read_rows(path):
    rows = []
    for encoding in ("utf-8-sig", "utf-16", "latin-1"):
        try:
            with open(path, "r", encoding=encoding) as f:
                reader = csv.reader(f, delimiter=";")
                for row in reader:
                    if row and any(x.strip() for x in row):
                        rows.append(row)
            return rows
        except UnicodeDecodeError:
            continue
    return rows

def row_timestamp(row):
    try:
        return int(row[2])
    except (IndexError, ValueError, TypeError):
        return 0

PARTICIPANT_LABEL = "Participant"

def apply_participant_label(rows):
    if not rows:
        return rows

    labeled = [list(row) for row in rows]
    for row in labeled:
        row[0] = ""

    labeled[0][0] = PARTICIPANT_LABEL
    return labeled

def rewrite_event_rows(session, session_index):
    rewritten = []

    for row in session:
        new_row = list(row)

        while len(new_row) < 10:
            new_row.append("")

        new_row[1] = str(session_index)

        # Remove only the duplicate receivedAtMs column.
        del new_row[9]

        rewritten.append(new_row)

    return apply_participant_label(rewritten)

def entered_pin_from_events(events_slice):
    digits = []
    for row in events_slice:
        if len(row) > 6 and row[3] == "SELECT":
            digits.append(str(row[6]))
    return "".join(digits)

def remap_trial_row(row, session_index):
    new_row = list(row)
    while len(new_row) < 13:
        new_row.append("")
    new_row[1] = str(session_index)
    return new_row

def make_trial_row(session_index, target_pin, entered_pin, correct, source_row=None, condition="SingleStrip"):
    if source_row and len(source_row) >= 13:
        return remap_trial_row([
            source_row[0], source_row[1], target_pin or source_row[2], entered_pin,
            str(correct).lower(), source_row[5], source_row[6], source_row[7],
            source_row[8], source_row[9], source_row[10], source_row[11], source_row[12],
        ], session_index)

    return [
        "",
        str(session_index),
        target_pin,
        entered_pin,
        str(correct).lower(),
        "0", "0", "0", "0", "0", "0", "0",
        condition,
    ]

def split_test_sessions(events):
    """One test = Enter Pin until Success or return to menu (Cancel)."""
    sessions = []
    current = None

    for row in events:
        event_type = row[3] if len(row) > 3 else ""

        if event_type == SESSION_START:
            if current is not None:
                sessions.append(current)
            current = [row]
            continue

        if current is not None:
            current.append(row)
            if event_type in SESSION_END:
                sessions.append(current)
                current = None

    if current is not None:
        sessions.append(current)

    return sessions

def build_trials_for_session(session, all_trials, trial_pointers, session_index):
    if not session:
        return []

    original_participant = session[0][0] if session[0] else ""
    pointer = trial_pointers.get(original_participant, 0)
    participant_trials = [t for t in all_trials if t[0] == original_participant]

    target_pin = ""
    condition = "SingleStrip"

    if participant_trials:
        target_pin = participant_trials[0][2] if len(participant_trials[0]) > 2 else ""
        condition = participant_trials[0][12] if len(participant_trials[0]) > 12 else "SingleStrip"

    result_rows = []
    attempt_events = []

    def take_next_trial(want_correct):
        nonlocal pointer, target_pin, condition

        while pointer < len(participant_trials):
            candidate = participant_trials[pointer]
            pointer += 1

            if len(candidate) <= 4:
                continue

            is_correct = candidate[4].strip().lower() == "true"

            if is_correct == want_correct:
                if not target_pin and len(candidate) > 2:
                    target_pin = candidate[2]

                if len(candidate) > 12 and candidate[12]:
                    condition = candidate[12]

                return candidate

        return None

    for row in session:
        event_type = row[3] if len(row) > 3 else ""
        attempt_events.append(row)

        if event_type == "TRIAL_END_FAILED":
            matched = take_next_trial(False)

            if matched:
                result_rows.append(remap_trial_row(matched, session_index))
            else:
                entered = entered_pin_from_events(attempt_events)
                result_rows.append(make_trial_row(
                    session_index,
                    target_pin,
                    entered,
                    False,
                    condition=condition
                ))

            attempt_events = []

        elif event_type == "TRIAL_END_SUCCESS":
            matched = take_next_trial(True)

            if matched:
                result_rows.append(remap_trial_row(matched, session_index))
            else:
                entered = entered_pin_from_events(attempt_events)
                result_rows.append(make_trial_row(
                    session_index,
                    target_pin,
                    entered,
                    True,
                    condition=condition
                ))

            attempt_events = []

        elif event_type == "TRIAL_CANCELLED":
            result_rows.append(make_trial_row(
                session_index,
                target_pin,
                "CANCELLED",
                False,
                condition=condition
            ))

            attempt_events = []

    trial_pointers[original_participant] = pointer
    return apply_participant_label(result_rows)

def folder_name(session_index):
    return f"{session_index}_Test"

def make_excel(rows, xlsx_path, headers, color):
    wb = Workbook()
    ws = wb.active
    ws.title = "Data"

    fill = PatternFill("solid", fgColor=color)
    thin = Side(style="thin", color="D9D9D9")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)

    for col, h in enumerate(headers, 1):
        cell = ws.cell(row=1, column=col, value=h)
        cell.fill = fill
        cell.font = Font(bold=True)
        cell.alignment = Alignment(horizontal="center")
        cell.border = border

    for r, row in enumerate(rows, 2):
        for c, value in enumerate(row, 1):
            cell = ws.cell(row=r, column=c, value=value)
            cell.border = border
            cell.alignment = Alignment(horizontal="center")

    ws.freeze_panes = "A2"
    ws.auto_filter.ref = ws.dimensions

    for col in range(1, len(headers) + 1):
        ws.column_dimensions[get_column_letter(col)].width = max(
            14,
            len(headers[col - 1]) + 2
        )

    wb.save(xlsx_path)

events = read_rows(events_path)
trials = read_rows(trials_path)

for name in os.listdir(base_dir):
    if re.match(r"^\d+_Test(_\d+)?$", name):
        shutil.rmtree(
            os.path.join(base_dir, name),
            ignore_errors=True
        )

sessions = split_test_sessions(events)
trial_pointers = {}
created_folders = []

for session_index, session in enumerate(sessions, start=1):
    if not session:
        continue

    folder = os.path.join(
        base_dir,
        folder_name(session_index)
    )

    os.makedirs(folder, exist_ok=True)

    event_rows = rewrite_event_rows(
        session,
        session_index
    )

    trial_rows = build_trials_for_session(
        session,
        trials,
        trial_pointers,
        session_index
    )

    trial_rows = [row[:12] for row in trial_rows]

    make_excel(
        event_rows,
        os.path.join(folder, "events.xlsx"),
        events_headers,
        "FFFF00"
    )

    make_excel(
        trial_rows,
        os.path.join(folder, "trials.xlsx"),
        trials_headers,
        "9DC3E6"
    )

    with open(
        os.path.join(folder, "events.csv"),
        "w",
        encoding="utf-8",
        newline=""
    ) as f:
        csv.writer(
            f,
            delimiter=";"
        ).writerows(event_rows)

    with open(
        os.path.join(folder, "trials.csv"),
        "w",
        encoding="utf-8",
        newline=""
    ) as f:
        csv.writer(
            f,
            delimiter=";"
        ).writerows(trial_rows)

    created_folders.append(
        os.path.basename(folder)
    )

print(
    "Created/updated:",
    ", ".join(created_folders)
)
"@

$tmpPy = Join-Path $tempDir "export_split.py"
$pythonCode | Out-File -Encoding UTF8 $tmpPy

python $tmpPy

Remove-Item $tempDir -Recurse -Force

Write-Host "Done."