"""验证真实 HTTP 流程，测试认证服务仅在本测试进程创建。"""
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import app


class Auth(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        token = self.headers.get('Authorization', '')
        body = json.dumps({'data': {'id': token[7:]}} if token in ('Bearer alice', 'Bearer bob') else {'data': None}).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args: object) -> None:
        pass


class Workflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        app.ROOT = Path(tempfile.mkdtemp(prefix='manual-label-api-test-'))
        app.initialize()
        cls.auth = ThreadingHTTPServer(('127.0.0.1', 0), Auth)
        cls.http = ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        app.AUTH = 'http://127.0.0.1:%s/auth' % cls.auth.server_port
        cls.base = 'http://127.0.0.1:%s/label-api' % cls.http.server_port
        for server in (cls.auth, cls.http):
            threading.Thread(target=server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.http.shutdown()
        cls.auth.shutdown()
        print('测试数据保留目录：', app.ROOT)

    def request(self, path: str, method: str = 'GET', body: object = None, token: str = 'alice', offset: int = 0) -> tuple:
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        request = Request(self.base + path, data=data, method=method, headers={'Authorization': 'Bearer ' + token, 'X-Upload-Offset': str(offset)})
        try:
            with urlopen(request, timeout=15) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def test_workflow(self) -> None:
        self.assertEqual(self.request('/projects', token='invalid')[0], 401)
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as archive:
            archive.writestr('run.csv', 'sample,value\n0,1\n1,2\n2,5\n')
            archive.writestr('README.txt', '说明')
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, 'w') as archive:
            archive.writestr('nested.zip', inner.getvalue())
        data = outer.getvalue()
        code, project = self.request('/projects', 'POST', {'name': '工业时序演示', 'filename': 'data.zip', 'size': len(data)})
        self.assertEqual(code, 200)
        pid = project['id']
        self.assertEqual(self.request('/projects/' + pid, token='bob')[0], 404)
        self.assertEqual(self.request('/projects/' + pid + '/chunk', 'PUT', data[:100])[1]['offset'], 100)
        self.assertEqual(self.request('/projects/' + pid + '/chunk', 'PUT', data[:100])[0], 409)
        self.assertEqual(self.request('/projects/' + pid + '/complete', 'POST')[0], 400)
        app.initialize()
        self.assertEqual(self.request('/projects')[1][0]['offset'], 100)
        self.assertEqual(self.request('/projects/' + pid + '/check', 'POST', b'bad')[0], 400)
        self.assertEqual(self.request('/projects/' + pid + '/check', 'POST', data[:100])[0], 200)
        self.assertEqual(self.request('/projects/' + pid + '/chunk', 'PUT', data[100:], offset=100)[0], 200)
        self.assertEqual(self.request('/projects/' + pid + '/complete', 'POST')[0], 200)
        for _ in range(100):
            if self.request('/projects')[1][0]['status'] == 'ready':
                break
            time.sleep(.02)
        code, listing = self.request('/projects/' + pid)
        self.assertEqual(code, 200)
        self.assertEqual(listing['total'], 1)
        self.assertEqual(listing['name'], '工业时序演示')
        self.assertEqual(self.request('/projects')[1][0]['filename'], 'data.zip')
        asset = listing['items'][0]
        self.assertEqual(self.request('/assets/' + asset['id'] + '/content', token='bob')[0], 404)
        initial_export = self.request('/projects/' + pid + '/export')[1]
        self.assertEqual(initial_export['file_count'], 1)
        self.assertEqual(initial_export['annotated_file_count'], 0)
        self.assertEqual(initial_export['items'][0]['marks'], [])
        self.assertEqual(len(self.request('/assets/' + asset['id'] + '/content')[1]['rows']), 3)
        annotation = {'marks': [{'label': '异常', 'start': 1, 'end': 2, 'details': {'state': '异常'}}], 'done': True, 'revision': 0}
        path = '/assets/' + asset['id'] + '/annotation'
        self.assertEqual(self.request(path, 'PUT', annotation)[0], 200)
        self.assertEqual(self.request(path, 'PUT', annotation)[0], 409)
        app.initialize()
        self.assertTrue(self.request('/projects/' + pid)[1]['items'][0]['done'])
        export = self.request('/projects/' + pid + '/export')[1]
        self.assertEqual(export['project_name'], '工业时序演示')
        self.assertEqual(export['source_filename'], 'data.zip')
        self.assertEqual(export['file_count'], 1)
        self.assertEqual(export['annotated_file_count'], 1)
        self.assertEqual(export['items'][0]['marks'], annotation['marks'])
        self.assertEqual(self.request('/projects/' + pid + '?page=1')[1]['items'], [])
        with self.assertRaises(ValueError):
            app.safe_name('../../outside')
        annotation['revision'] = 1
        annotation['marks'][0]['end'] = 1000
        self.assertEqual(self.request(path, 'PUT', annotation)[0], 400)


if __name__ == '__main__':
    unittest.main()
