"""人工标注演示后端：分块上传、归档索引、按需读取、独立保存标注。"""
import csv
from contextlib import contextmanager
from typing import Iterator
import io
import json
import math
import mimetypes
import os
from pathlib import Path, PurePosixPath
import shutil
import sqlite3
import tarfile
import threading
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(os.environ.get('LABEL_DATA_DIR', str(Path(__file__).parent / 'data')))
AUTH = os.environ.get('LABEL_AUTH_URL', 'http://127.0.0.1:8080/api/user/current-user')
MAX_UPLOAD = int(os.environ.get('LABEL_MAX_UPLOAD_GB', '10')) * 1024**3
MAX_EXPANDED = int(os.environ.get('LABEL_MAX_EXPANDED_GB', '20')) * 1024**3
CHUNK = 8 * 1024**2
LOCK = threading.RLock()
POOL = ThreadPoolExecutor(max_workers=1)
IMAGE = {'.jpg', '.jpeg', '.png', '.webp'}
ARCHIVES = {'.zip', '.tar', '.gz', '.tgz'}


@contextmanager
def database() -> Iterator[sqlite3.Connection]:
    db = sqlite3.connect(ROOT / 'index.sqlite', timeout=30)
    db.row_factory = sqlite3.Row
    try:
        with db:
            yield db
    finally:
        db.close()


def initialize() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    with database() as db:
        db.executescript('''
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY, owner TEXT, name TEXT, size INTEGER,
            status TEXT, error TEXT DEFAULT '', created REAL, count INTEGER DEFAULT 0, skipped INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS assets(id TEXT PRIMARY KEY, project TEXT, name TEXT, path TEXT, kind TEXT,
            marks TEXT DEFAULT '[]', done INTEGER DEFAULT 0, revision INTEGER DEFAULT 0);
        CREATE INDEX IF NOT EXISTS asset_project ON assets(project);
        ''')
        # 保留旧项目及原始上传文件名。
        if 'filename' not in {row['name'] for row in db.execute('PRAGMA table_info(projects)')}:
            db.execute("ALTER TABLE projects ADD COLUMN filename TEXT NOT NULL DEFAULT ''")
            db.execute('UPDATE projects SET filename=name')
        db.execute("UPDATE projects SET status='error',error='服务重启中断了解压，可点击重新处理' WHERE status='processing'")


def safe_name(name: str) -> str:
    p = PurePosixPath(name.replace('\\', '/'))
    if p.is_absolute() or '..' in p.parts or not p.parts or ':' in p.parts[0]:
        raise ValueError('归档包含不安全的路径')
    return str(p)


def parse_series(path: Path) -> dict:
    if path.stat().st_size > 64 * 1024**2:
        raise ValueError('单个时序文件超过 64MB，请按运行批次拆分；归档本身支持大文件')
    with path.open(encoding='utf-8-sig') as f:
        if path.suffix.lower() == '.dat':
            rows = [line.split() for line in f if line.strip()]
            if len(rows) == 52 and all(len(row) == 500 for row in rows):
                rows = list(map(list, zip(*rows)))
            if not rows or any(len(row) != 52 or any(not math.isfinite(float(v)) for v in row) for row in rows):
                raise ValueError('DAT 必须包含 52 个数值通道')
            columns = ['sample_index'] + ['XMEAS_' + str(i) for i in range(1, 42)] + ['XMV_' + str(i) for i in range(1, 12)]
            rows = [[str(i)] + row for i, row in enumerate(rows)]
        else:
            reader = csv.reader(f)
            columns = next(reader, [])
            rows = [r for r in reader if any(v.strip() for v in r)]
            if len(columns) < 2 or not rows or any(len(r) != len(columns) for r in rows):
                raise ValueError('CSV 必须有表头且列数一致')
            if not any(is_number(v) for r in rows for v in r[1:]):
                raise ValueError('CSV 没有数值通道，可能是说明或索引文件')
    if len(rows) > 50000:
        raise ValueError('单个时序文件最多 50000 个采样点，请拆分运行批次')
    return {'columns': columns, 'rows': rows}


def is_number(value: str) -> bool:
    try:
        return bool(value.strip()) and math.isfinite(float(value))
    except ValueError:
        return False


def process_project(pid: str) -> None:
    folder = ROOT / pid
    try:
        with database() as db:
            project = db.execute('SELECT * FROM projects WHERE id=?', (pid,)).fetchone()
            db.execute('DELETE FROM assets WHERE project=?', (pid,))
        count, skipped, expanded = 0, 0, 0

        def consume(name: str, size: int, stream: object, depth: int) -> None:
            nonlocal count, skipped, expanded
            name = safe_name(name)
            suffix = PurePosixPath(name).suffix.lower()
            # 即使不导入的 TAR 成员也要计入展开预算，避免跳过大文件时无限解压。
            expanded += size
            if size < 0 or expanded > MAX_EXPANDED or size > MAX_UPLOAD or count >= 20000:
                raise ValueError('归档展开后超过容量或文件数量限制')
            if suffix not in IMAGE | ARCHIVES | {'.csv', '.dat'} or '__MACOSX' in name.split('/') or '/Masks/' in '/' + name:
                skipped += 1
                return
            if shutil.disk_usage(ROOT).free < size + 512 * 1024**2:
                raise ValueError('服务器可用磁盘空间不足')
            aid = uuid.uuid4().hex
            target = folder / (aid + suffix)
            written = 0
            with target.open('wb') as output:
                while True:
                    data = stream.read(min(1024**2, size - written + 1))
                    if not data:
                        break
                    written += len(data)
                    if written > size:
                        raise ValueError('归档文件大小与声明不符')
                    output.write(data)
            if written != size:
                raise ValueError('归档内容不完整')
            if suffix in ARCHIVES:
                unpack(target, name + '/', depth + 1)
                return
            if suffix in {'.csv', '.dat'}:
                try:
                    parse_series(target)
                except (ValueError, UnicodeError, csv.Error):
                    skipped += 1
                    return
            kind = 'image' if suffix in IMAGE else 'series'
            with database() as db:
                db.execute('INSERT INTO assets(id,project,name,path,kind) VALUES(?,?,?,?,?)', (aid, pid, name, target.name, kind))
                count += 1
                if count % 50 == 0:
                    db.execute('UPDATE projects SET count=?,skipped=? WHERE id=?', (count, skipped, pid))

        def unpack(path: Path, prefix: str, depth: int) -> None:
            if depth > 3:
                raise ValueError('最多支持三层嵌套归档')
            if zipfile.is_zipfile(path):
                with zipfile.ZipFile(path) as archive:
                    if len(archive.infolist()) > 30000:
                        raise ValueError('归档目录条目过多')
                    for entry in archive.infolist():
                        safe_name(entry.filename)
                        if entry.is_dir():
                            continue
                        if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                            raise ValueError('归档不允许符号链接')
                        with archive.open(entry) as stream:
                            consume(prefix + entry.filename, entry.file_size, stream, depth)
            else:
                with tarfile.open(path, 'r:*') as archive:
                    for index, entry in enumerate(archive):
                        if index > 30000:
                            raise ValueError('归档目录条目过多')
                        safe_name(entry.name)
                        if entry.isdir():
                            continue
                        if not entry.isfile():
                            raise ValueError('归档不允许链接或特殊设备文件')
                        with archive.extractfile(entry) as stream:
                            consume(prefix + entry.name, entry.size, stream, depth)

        original = folder / 'original.upload'
        if Path(project['filename']).suffix.lower() in ARCHIVES:
            unpack(original, '', 0)
        else:
            with original.open('rb') as stream:
                consume(project['filename'], original.stat().st_size, stream, 0)
        if not count:
            raise ValueError('没有找到可标注的图像或数值时序数据')
        with database() as db:
            db.execute("UPDATE projects SET status='ready',count=?,skipped=?,error='' WHERE id=?", (count, skipped, pid))
    except Exception as error:
        with database() as db:
            db.execute("UPDATE projects SET status='error',error=? WHERE id=?", (str(error)[:400], pid))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args: object) -> None:
        pass

    def send_json(self, value: object, status: int = 200) -> None:
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)

    def body(self, maximum: int = 1024**2) -> bytes:
        length = int(self.headers.get('Content-Length', '-1'))
        if length < 0 or length > maximum:
            raise ValueError('请求大小超出限制')
        data = self.rfile.read(length)
        if len(data) != length:
            raise ValueError('上传分块不完整，请重试')
        return data

    def owner(self) -> str:
        token = self.headers.get('Authorization', '')
        if not token.startswith('Bearer '):
            raise PermissionError('请先登录')
        try:
            with urlopen(Request(AUTH, headers={'Authorization': token}), timeout=10) as response:
                user = json.load(response).get('data')
            if not user or user.get('id') is None:
                raise PermissionError('登录已过期')
            return str(user['id'])
        except HTTPError:
            raise PermissionError('登录已过期')

    def project(self, pid: str, owner: str) -> dict:
        with database() as db:
            row = db.execute('SELECT * FROM projects WHERE id=? AND owner=?', (pid, owner)).fetchone()
        if row is None:
            raise FileNotFoundError('项目不存在')
        return dict(row)

    def asset(self, aid: str, owner: str) -> dict:
        with database() as db:
            row = db.execute('SELECT a.* FROM assets a JOIN projects p ON a.project=p.id WHERE a.id=? AND p.owner=? AND p.status=\'ready\'', (aid, owner)).fetchone()
        if row is None:
            raise FileNotFoundError('文件不存在')
        return dict(row)

    def route(self) -> None:
        url = urlsplit(self.path)
        route = url.path.removeprefix('/label-api').strip('/').split('/')
        if route == ['health']:
            self.send_json({'ok': True})
            return
        owner = self.owner()
        if route == ['projects']:
            if self.command == 'GET':
                with database() as db:
                    rows = db.execute('SELECT * FROM projects WHERE owner=? ORDER BY created DESC LIMIT 100', (owner,)).fetchall()
                result = []
                for row in rows:
                    p = dict(row)
                    path = ROOT / p['id'] / 'original.upload'
                    p['offset'] = path.stat().st_size if path.exists() else 0
                    result.append(p)
                self.send_json(result)
            elif self.command == 'POST':
                data = json.loads(self.body())
                name, size = data.get('name'), data.get('size')
                filename = data.get('filename', name)
                if not isinstance(name, str) or not name.strip() or len(name) > 240:
                    raise ValueError('请填写项目名称（最多240个字符）')
                name = name.strip()
                if not isinstance(filename, str) or len(filename) > 240 or Path(filename).suffix.lower() not in IMAGE | ARCHIVES | {'.csv', '.dat'} or type(size) is not int or not 0 < size <= MAX_UPLOAD:
                    raise ValueError('请上传 ZIP/TAR、图像或时序文件；单个上传限配置容量（默认10GB）')
                with LOCK, database() as db:
                    reserved = db.execute("SELECT COALESCE(SUM(size),0) FROM projects WHERE status='uploading'").fetchone()[0]
                    if shutil.disk_usage(ROOT).free < reserved + size * 2 + 1024**3:
                        raise ValueError('服务器可用空间不足，请联系管理员')
                    pid = uuid.uuid4().hex
                    (ROOT / pid).mkdir()
                    (ROOT / pid / 'original.upload').touch()
                    db.execute('INSERT INTO projects(id,owner,name,filename,size,status,created) VALUES(?,?,?,?,?,?,?)', (pid, owner, name, filename, size, 'uploading', time.time()))
                self.send_json({'id': pid, 'offset': 0})
            else:
                raise ValueError('不支持的请求')
        elif len(route) == 3 and route[0] == 'projects' and route[2] == 'chunk' and self.command == 'PUT':
            pid = route[1]
            with LOCK:
                project = self.project(pid, owner)
                target = ROOT / pid / 'original.upload'
                offset = int(self.headers.get('X-Upload-Offset', '-1'))
                if project['status'] != 'uploading' or offset != target.stat().st_size:
                    self.send_json({'error': '上传位置已变化，请继续上传', 'offset': target.stat().st_size}, 409)
                    return
                data = self.body(CHUNK)
                if not data or offset + len(data) > project['size']:
                    raise ValueError('上传分块大小错误')
                with target.open('ab') as output:
                    output.write(data)
                    output.flush()
                    os.fsync(output.fileno())
                self.send_json({'offset': target.stat().st_size})
        elif len(route) == 3 and route[0] == 'projects' and route[2] == 'check' and self.command == 'POST':
            project = self.project(route[1], owner)
            data = self.body(CHUNK)
            with (ROOT / project['id'] / 'original.upload').open('rb') as original:
                if not data or original.read(len(data)) != data:
                    raise ValueError('文件内容与已上传部分不同，请选择原文件或创建新项目')
            self.send_json({'ok': True})
        elif len(route) == 3 and route[0] == 'projects' and route[2] == 'complete' and self.command == 'POST':
            pid = route[1]
            with LOCK:
                project = self.project(pid, owner)
                if project['status'] not in ('uploading', 'error'):
                    raise ValueError('该项目已提交处理')
                if (ROOT / pid / 'original.upload').stat().st_size != project['size']:
                    raise ValueError('文件尚未完整上传')
                with database() as db:
                    db.execute("UPDATE projects SET status='processing',error='' WHERE id=?", (pid,))
                POOL.submit(process_project, pid)
            self.send_json({'ok': True})
        elif len(route) == 2 and route[0] == 'projects' and self.command == 'GET':
            project = self.project(route[1], owner)
            if project['status'] != 'ready':
                raise ValueError('数据尚未处理完成')
            page = max(0, int(parse_qs(url.query).get('page', ['0'])[0]))
            with database() as db:
                rows = db.execute('SELECT id,name,kind,marks,done,revision FROM assets WHERE project=? ORDER BY name,id LIMIT 50 OFFSET ?', (route[1], page * 50)).fetchall()
            items = [dict(row) for row in rows]
            for item in items:
                item['marks'] = json.loads(item['marks'])
                item['done'] = bool(item['done'])
            self.send_json({'items': items, 'total': project['count'], 'name': project['name']})
        elif len(route) == 3 and route[0] == 'assets':
            asset = self.asset(route[1], owner)
            path = ROOT / asset['project'] / asset['path']
            if route[2] == 'content' and self.command == 'GET':
                if asset['kind'] == 'series':
                    self.send_json(parse_series(path))
                else:
                    self.send_response(200)
                    self.send_header('Content-Type', mimetypes.guess_type(path.name)[0] or 'application/octet-stream')
                    self.send_header('Content-Length', str(path.stat().st_size))
                    self.send_header('Cache-Control', 'private, no-store')
                    self.end_headers()
                    with path.open('rb') as source:
                        shutil.copyfileobj(source, self.wfile, 1024**2)
            elif route[2] == 'annotation' and self.command == 'PUT':
                data = json.loads(self.body())
                validate_annotation(data, asset['kind'], path)
                with database() as db:
                    cursor = db.execute('UPDATE assets SET marks=?,done=?,revision=revision+1 WHERE id=? AND revision=?', (json.dumps(data['marks'], ensure_ascii=False), int(data['done']), asset['id'], data['revision']))
                    if cursor.rowcount != 1:
                        self.send_json({'error': '标注已在其他页面修改，请刷新后再编辑'}, 409)
                        return
                self.send_json({'revision': data['revision'] + 1})
            else:
                raise FileNotFoundError('接口不存在')
        elif len(route) == 3 and route[0] == 'projects' and route[2] == 'export' and self.command == 'GET':
            project = self.project(route[1], owner)
            with database() as db:
                rows = db.execute('SELECT id,name,kind,marks,done FROM assets WHERE project=? ORDER BY name', (route[1],)).fetchall()
            items = [{'id': r['id'], 'name': r['name'], 'kind': r['kind'],
                      'marks': json.loads(r['marks']), 'done': bool(r['done'])}
                     for r in rows]
            annotated_file_count = sum(bool(item['marks']) for item in items)
            self.send_json({'format': 'server-annotations-v1', 'project_id': route[1],
                            'project_name': project['name'], 'source_filename': project['filename'],
                            'file_count': len(items), 'annotated_file_count': annotated_file_count,
                            'items': items})
        else:
            raise FileNotFoundError('接口不存在')

    def dispatch(self) -> None:
        self.connection.settimeout(120)
        try:
            self.route()
        except PermissionError as e:
            self.send_json({'error': str(e)}, 401)
        except FileNotFoundError as e:
            self.send_json({'error': str(e)}, 404)
        except (ValueError, KeyError, TypeError) as e:
            self.send_json({'error': str(e)}, 400)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.send_json({'error': '服务处理失败，请检查磁盘或登录服务状态后重试'}, 500)

    do_GET = do_POST = do_PUT = dispatch


def validate_annotation(data: dict, kind: str, path: Path) -> None:
    if type(data.get('done')) is not bool or type(data.get('revision')) is not int or not isinstance(data.get('marks'), list) or len(data['marks']) > 10000:
        raise ValueError('标注格式错误')
    maximum = 1000 if kind == 'image' else len(parse_series(path)['rows']) - 1
    for mark in data['marks']:
        if not isinstance(mark.get('label'), str) or not mark['label'].strip() or len(mark['label']) > 2000:
            raise ValueError('标签不能为空或过长')
        a, b = mark.get('start'), mark.get('end')
        if type(a) not in (int, float) or type(b) not in (int, float) or not 0 <= a <= b <= maximum:
            raise ValueError('标注区间错误')
        if kind == 'series' and (int(a) != a or int(b) != b):
            raise ValueError('采样点必须为整数')
        if kind == 'image' and ('y' in mark or 'height' in mark):
            y, height = mark.get('y'), mark.get('height')
            if type(y) not in (int, float) or type(height) not in (int, float) or not 0 <= y < y + height <= 1000:
                raise ValueError('矩形范围错误')
        details = mark.get('details', {})
        if not isinstance(details, dict) or any(not isinstance(v, str) for v in details.values()):
            raise ValueError('标签属性格式错误')


if __name__ == '__main__':
    initialize()
    server = ThreadingHTTPServer((os.environ.get('LABEL_HOST', '127.0.0.1'), int(os.environ.get('LABEL_PORT', '8013'))), Handler)
    print('标注后端已启动', server.server_address, flush=True)
    server.serve_forever()
