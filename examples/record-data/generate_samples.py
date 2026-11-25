"""Generate small, non-production fixtures for all seven supported input formats."""
import argparse
import json
from pathlib import Path
import tempfile
import zipfile


def generate(destination):
    from openpyxl import Workbook
    import pyarrow as pa
    import pyarrow.parquet as pq
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        (root / 'rows.csv').write_text('id,amount,label\n001,0,原始值\n002,-2,\n', encoding='utf-8')
        book = Workbook()
        book.active.append(['id', 'amount'])
        book.active.append(['001', 0])
        book.active.append(['002', None])
        book.save(root / 'rows.xlsx')
        pq.write_table(pa.Table.from_pylist([{'id': '001', 'amount': 0}, {'id': '002', 'amount': None}]), root / 'rows.parquet')
        document = {'id': '001', 'customer': {'name': '张三', 'company': '东南大学'}, 'amount': 0}
        (root / 'document.json').write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding='utf-8')
        (root / 'records.jsonl').write_text(json.dumps(document, ensure_ascii=False) + '\n' + json.dumps({'id': '002', 'customer': {'name': '李四'}}, ensure_ascii=False) + '\n', encoding='utf-8')
        (root / 'document.xml').write_text('<record id="001"><customer>张三</customer><amount>0</amount></record>', encoding='utf-8')
        (root / 'document.yaml').write_text('id: "001"\ncustomer:\n  name: 张三\namount: 0\n', encoding='utf-8')
        for name, files in [('structured-sample.zip', ['rows.csv', 'rows.xlsx', 'rows.parquet']), ('documents-sample.zip', ['document.json', 'records.jsonl', 'document.xml', 'document.yaml'])]:
            with zipfile.ZipFile(destination / name, 'w', zipfile.ZIP_DEFLATED) as archive:
                for filename in files:
                    info = zipfile.ZipInfo(filename, (2026, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(info, (root / filename).read_bytes())
    print(destination.resolve())


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('destination', type=Path)
    generate(parser.parse_args().destination)
