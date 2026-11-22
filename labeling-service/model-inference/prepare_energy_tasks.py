"""Build native station-day tasks from the anonymized energy archive."""

import argparse
import csv
from datetime import date, timedelta
import io
import json
from pathlib import Path
from statistics import median
from zipfile import ZipFile, ZIP_DEFLATED


def percentile(values, fraction):
    ordered = sorted(values)
    floor, remainder = divmod((len(ordered) - 1) * fraction, 1)
    index = int(floor)
    return ordered[index] + remainder * (ordered[min(index + 1, len(ordered) - 1)] - ordered[index])


def build_tasks(archive, station, dates):
    source, = [name for name in archive.namelist() if name.endswith("/data/hourly.csv")]
    rows = {}
    first, last = min(dates) - timedelta(days=30), max(dates)
    with archive.open(source) as raw, io.TextIOWrapper(raw, encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            if row["station_key"] != station:
                continue
            current = date.fromisoformat(row["date_local"])
            if not first <= current <= last:
                continue
            hour = int(row["hour_local"])
            if hour not in range(24) or hour in rows.get(current, {}):
                raise ValueError(f"Invalid or duplicate hour: {current} {hour}")
            rows.setdefault(current, {})[hour] = float(row["value_kwh"]) if row["value_kwh"] else None
    daily_source, = [name for name in archive.namelist() if name.endswith("/data/daily.csv")]
    daily = {}
    with archive.open(daily_source) as raw, io.TextIOWrapper(raw, encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            if row["station_key"] != station:
                continue
            current = date.fromisoformat(row["date_local"])
            if first <= current <= last and row["value_kwh"]:
                if current in daily:
                    raise ValueError(f"Duplicate daily total: {current}")
                daily[current] = float(row["value_kwh"])
    for current in dates:
        if current not in rows:
            raise ValueError(f"No records found for {station} on {current}")
        values = [rows[current].get(hour) for hour in range(24)]
        history_days = [rows.get(current - timedelta(days=days), {}) for days in range(1, 31)]
        history = [value for hours in history_days for value in hours.values()
                   if len(hours) == 24 and value is not None]
        summary = {"days": sum(len(hours) == 24 and None not in hours.values() for hours in history_days)}
        if len(history) == 720:
            summary.update(hourlyP5=percentile(history, 0.05), hourlyP99=percentile(history, 0.99))
        daily_history = [daily.get(current - timedelta(days=days)) for days in range(1, 31)]
        totals = [value for value in daily_history if value is not None and value >= 0]
        summary["dailyDays"] = len(totals)
        if len(totals) == 30:
            summary["dailyMedian"] = median(totals)
        yield {
            "meta": {"displayName": f"{station} · {current}"},
            "data": {"station": station, "date": current.isoformat(),
                     "context": f"站点 {station} · {current} · 完整历史 {summary['days']}/30 天",
                     "history_summary": summary, "daily_kwh": daily.get(current),
                     "series": {"hour_local": list(range(24)), "value_kwh": values}},
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--station", required=True)
    parser.add_argument("--date", type=date.fromisoformat, action="append", required=True)
    args = parser.parse_args()
    with ZipFile(args.source) as archive:
        tasks = list(build_tasks(archive, args.station, sorted(set(args.date))))
    with ZipFile(args.output, "x", compression=ZIP_DEFLATED) as bundle:
        bundle.writestr("tasks.json", json.dumps(tasks, ensure_ascii=False, allow_nan=False))


if __name__ == "__main__":
    main()
