"""Numeric evidence for the station-day energy template."""

from decimal import Decimal
from itertools import groupby
import math

DEFAULT_RULES = {
    "lowDailyPercent": 50,
    "highDailyPercent": 150,
    "dropPercent": 50,
    "spikePercent": 150,
}


def threshold_at_percent(baseline, percent):
    return Decimal(str(baseline)) * Decimal(str(percent)) / 100


def valid_baseline(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def energy_candidates(times, values, context, rules):
    if times != list(range(24)):
        raise ValueError("Station-day input requires hour coordinates 0 to 23")
    candidates = []

    def append(label, start, end, detail):
        candidates.append(dict(label=label, start=start, end=end, evidence=detail))

    missing = [hour for hour, value in enumerate(values) if value is None]
    for _, group in groupby(enumerate(missing), key=lambda item: item[1] - item[0]):
        hours = [hour for _, hour in group]
        append("缺测", hours[0], hours[-1], {"missingHours": len(hours)})

    if all(value == 0 for value in values):
        append("全天零能耗", 0, 23, {"zeroHours": 24})
    else:
        for is_zero, group in groupby(range(6, 23), key=lambda hour: values[hour] == 0):
            hours = list(group)
            if is_zero and len(hours) >= 6:
                append("部分时段零能耗", hours[0], hours[-1], {"zeroHours": len(hours)})

    history = context if isinstance(context, dict) else {}
    if history.get("days") == 30:
        for label, key, rule in (("尖峰", "hourlyP99", "spikePercent"), ("跌落", "hourlyP5", "dropPercent")):
            baseline = history.get(key)
            if not valid_baseline(baseline):
                continue
            threshold = threshold_at_percent(baseline, rules[rule])
            for hour, value in enumerate(values):
                if value is None:
                    continue
                reading = Decimal(str(value))
                triggered = reading > threshold if label == "尖峰" else 6 <= hour <= 22 and reading < threshold
                if triggered:
                    append(label, hour, hour, {"valueKwh": value, "thresholdKwh": float(threshold)})
    return {f"c{index + 1}": candidate for index, candidate in enumerate(candidates)}


def select_energy_candidates(candidates, selected_ids):
    if not isinstance(selected_ids, list) or any(
        not isinstance(item, str) or item not in candidates for item in selected_ids
    ):
        raise ValueError("Model selected an unknown energy candidate")
    selected = set(selected_ids)
    return [candidate for key, candidate in candidates.items() if key in selected]


def day_candidates(values, context, events, labels, rules):
    """Return evidence for eligible daily labels without inferring operating causes."""
    allowed = set(labels)

    def eligible(candidates):
        return {label: reason for label, reason in candidates.items() if label in allowed}

    if any(value is None for value in values):
        return eligible({"缺测": f"24 个小时中有 {values.count(None)} 个缺测值"})
    if all(value == 0 for value in values):
        return eligible({"全天零能耗": "24 个小时均有记录，且数值均为 0 kWh"})

    history = context if isinstance(context, dict) else {}
    today, median = history.get("dailyKwh"), history.get("dailyMedian")
    has_daily_baseline = (history.get("dailyDays") == 30 and valid_baseline(today)
                          and valid_baseline(median) and median > 0)
    daily_anomaly = {}
    if has_daily_baseline:
        for label, rule in (("低负荷", "lowDailyPercent"), ("高负荷", "highDailyPercent")):
            threshold = threshold_at_percent(median, rules[rule])
            matches = Decimal(str(today)) < threshold if label == "低负荷" else Decimal(str(today)) > threshold
            if matches:
                daily_anomaly[label] = (f"当日总量 {today:.4g} kWh，前 30 日中位数 {median:.4g} kWh，"
                                        f"阈值 {threshold:.4g} kWh（{rules[rule]}%）")

    counts = {}
    for candidate in events.values():
        label = candidate["label"]
        counts[label] = counts.get(label, 0) + 1
    daily_anomaly.update({label: f"当天有 {count} 处符合阈值规则的{label}候选"
                          for label, count in counts.items()})
    if (not daily_anomaly and has_daily_baseline and history.get("days") == 30
            and all(valid_baseline(history.get(key)) for key in ("hourlyP99", "hourlyP5"))):
        return eligible({"正常": "全天记录完整，日总量在配置范围内，小时数据未触发异常规则"})
    return eligible(daily_anomaly)
