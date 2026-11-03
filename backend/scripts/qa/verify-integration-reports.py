"""核对真实依赖测试报告；缺项、旧报告和跳过都不能算通过。"""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(directory, suites, since_epoch):
    results = []
    if not suites or len(set(suites)) != len(suites):
        raise ValueError("套件清单为空或重复")
    for name in suites:
        path = Path(directory) / f"TEST-{name}.xml"
        if not path.is_file() or path.stat().st_mtime < since_epoch:
            raise ValueError(f"缺少本轮报告：{name}")
        root = ET.parse(path).getroot()
        if root.tag != "testsuite" or root.get("name") != name:
            raise ValueError(f"报告身份不匹配：{name}")
        cases = root.findall("testcase")
        total = int(root.attrib["tests"])
        if total <= 0 or total != len(cases):
            raise ValueError(f"没有实际用例或计数不一致：{name}")
        if any(int(root.attrib[key]) != 0 for key in ("failures", "errors", "skipped")):
            raise ValueError(f"存在失败、错误或跳过：{name}")
        if any(case.find(kind) is not None for case in cases for kind in ("failure", "error", "skipped")):
            raise ValueError(f"用例内容与通过汇总矛盾：{name}")
        results.append({"suite": name, "passed": total})
    return {"suites": results, "passed": sum(row["passed"] for row in results), "failed": 0, "skipped": 0}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path)
    parser.add_argument("--since-epoch", type=float, required=True, help="本轮 Maven 启动前记录的时间戳")
    args = parser.parse_args()
    suites = json.loads(Path(__file__).with_name("container-suites.json").read_text(encoding="utf-8"))
    try:
        print(json.dumps(verify(args.reports, suites, args.since_epoch), ensure_ascii=False, indent=2))
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        print(f"集成验收未通过：{error}", file=sys.stderr)
        sys.exit(1)
