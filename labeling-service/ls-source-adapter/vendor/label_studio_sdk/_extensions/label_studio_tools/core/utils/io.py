# Modified for this platform; see ls-source-adapter/SOURCE.md for changes.
import base64
import hashlib
import io
import logging
import os
import posixpath
import shutil
from contextlib import contextmanager
from contextvars import ContextVar
from tempfile import mkdtemp
from typing import Callable, Optional
from urllib.parse import parse_qs, urlparse

import requests
from appdirs import user_cache_dir, user_data_dir

from label_studio_sdk._extensions.label_studio_tools.core.utils.params import get_env

_DIR_APP_NAME = "label-studio"
# No default: without an explicit root, Local Storage files are never read from disk
LOCAL_FILES_DOCUMENT_ROOT = get_env("LOCAL_FILES_DOCUMENT_ROOT")
VERIFY_SSL = get_env("VERIFY_SSL", default=True, is_bool=True)

logger = logging.getLogger(__name__)

_local_files_resolver: ContextVar[Optional[Callable[[str], Optional[str]]]] = ContextVar(
    "local_files_resolver", default=None
)
_http_session: ContextVar[Optional[requests.Session]] = ContextVar("http_session", default=None)


def concat_urls(base_url, url):
    return base_url.rstrip("/") + "/" + url.lstrip("/")


def encode_presign_fileuri(cloud_uri: str) -> str:
    """Base64-urlsafe-encode a cloud URI for /tasks/<id>/presign/?fileuri=.

    Matches server-side resolve_uris / ResolveStorageUriAPIMixin (FIT-2611).
    """
    return base64.urlsafe_b64encode(cloud_uri.encode()).decode()


def is_cloud_storage_uri(url) -> bool:
    """True for Label Studio cloud source URIs (S3 / GCS / Azure Blob).

    Matches both ``s3://bucket/...`` and the historical ``s3:`` prefix check used
    throughout this module so scheme detection stays consistent (FIT-2611).
    """
    return isinstance(url, str) and (
        url.startswith("s3:") or url.startswith("gs:") or url.startswith("azure-blob:")
    )


def get_data_dir():
    data_dir = user_data_dir(appname=_DIR_APP_NAME)
    os.makedirs(data_dir, exist_ok=True)
    return data_dir


def get_cache_dir():
    cache_dir = user_cache_dir(appname=_DIR_APP_NAME)
    os.makedirs(cache_dir, exist_ok=True)
    return cache_dir


def safe_build_path(base_dir: str, user_path: str) -> str:
    combined_path = os.path.join(base_dir, user_path)
    absolute_path = os.path.abspath(combined_path)
    base_dir_abs = os.path.abspath(base_dir)

    if os.path.commonpath([absolute_path, base_dir_abs]) != base_dir_abs:
        raise ValueError(f"Invalid path: {user_path}")

    return absolute_path


@contextmanager
def local_files_resolver(resolver: Optional[Callable[[str], Optional[str]]]):
    """Route Local Storage lookups through ``resolver`` for the duration of the block.

    ``resolver`` receives the decoded ``?d=`` value of a ``/data/local-files/`` URL and returns
    the file path it may be read from, or None when the caller has no access to it. Label Studio
    installs one for server-side exports, because only the server knows which Local Files
    storages a project is allowed to read.
    """
    token = _local_files_resolver.set(resolver)
    try:
        yield
    finally:
        _local_files_resolver.reset(token)


@contextmanager
def http_session(session: Optional[requests.Session]):
    """Send media downloads through ``session`` for the duration of the block.

    Label Studio installs one for server-side exports, because task data can point at any URL and
    only the server knows which destinations it may reach (e.g. an SSRF-safe adapter).
    """
    token = _http_session.set(session)
    try:
        yield
    finally:
        _http_session.reset(token)


def http_get(url, **kwargs):
    raise ValueError("NETWORK_DISABLED: export resources must come from the platform manifest")


def _local_files_root() -> Optional[str]:
    if not LOCAL_FILES_DOCUMENT_ROOT:
        return None
    root = os.path.abspath(LOCAL_FILES_DOCUMENT_ROOT)
    # "/" would turn every readable file into a Local Storage file
    if root == os.path.abspath(os.sep):
        return None
    return root


def resolve_local_storage_file(url: str) -> Optional[str]:
    """Map a ``/data/local-files/?d=<path>`` URL to a path on disk.

    Returns None when the file should be fetched from Label Studio instead: no resolver is
    installed and LOCAL_FILES_DOCUMENT_ROOT is unset or "/". Raises FileNotFoundError when an
    installed resolver denies the path, so callers don't fetch it some other way.
    """
    # Parsed like request.GET.get("d") in the /data/local-files/ view: decoded once, the last value wins
    values = parse_qs(urlparse(url).query, keep_blank_values=True).get("d")
    relative_path = values[-1] if values else ""
    if not relative_path:
        raise FileNotFoundError(f"Local Storage URL has no file path: {url}")
    resolver = _local_files_resolver.get()
    if resolver is not None:
        filepath = resolver(relative_path)
        if filepath is None:
            raise FileNotFoundError(f"Local Storage file is not available: {relative_path}")
        return filepath

    root = _local_files_root()
    if root is None:
        return None
    # Same normalization as the /data/local-files/ view: "?d=" is always relative to the root
    return safe_build_path(root, posixpath.normpath(relative_path).lstrip("/"))


def is_jwt_well_formed(token: str) -> bool:
    import jwt  # Not needed by the platform offline resource resolver.
    try:
        jwt.get_unverified_header(token)
        return True
    except Exception:
        return False


def _build_headers(target_url, hostname, access_token):
    headers = {
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.97 Safari/537.36"
    }
    if access_token and hostname:
        if urlparse(target_url).netloc == urlparse(hostname).netloc:
            if is_jwt_well_formed(access_token):
                headers["Authorization"] = "Bearer " + access_token
            else:
                headers["Authorization"] = "Token " + access_token
    return headers


def _build_cache_path(cache_dir, target_url, filename):
    return os.path.join(cache_dir, hashlib.md5(target_url.encode(), usedforsecurity=False).hexdigest()[:8] + "__" + filename)


def get_local_path(url, cache_dir=None, project_dir=None, hostname=None,
                   image_dir=None, access_token=None, download_resources=True, task_id=None):
    # Platform adaptation: resolve ONLY resources explicitly supplied by the caller.
    from platform_resources import resolve_resource
    return resolve_resource(url, cache_dir if download_resources else None)


def download_and_cache(
    url,
    cache_dir,
    download_resources,
    hostname,
    access_token,
    is_local_storage_file,
    is_cloud_storage_file,
    is_storage_data_file,
    storage_filepath,
    fallback_upload_url=None,
):
    def _filename_for(target_url, storage_fp=None):
        parsed = urlparse(target_url)
        if is_local_storage_file:
            return os.path.basename(target_url.split("?d=")[1])
        if is_cloud_storage_file:
            # Prefer original cloud URI — download URL is a base64 /presign/ endpoint.
            return os.path.basename(storage_fp or target_url)
        if is_storage_data_file:
            sfp = storage_fp or parse_qs(parsed.query).get("filepath", [None])[0]
            name = os.path.basename(sfp or "")
            return name or os.path.basename(parsed.path)
        return os.path.basename(parsed.path)

    def _cache_path(target_url, fname):
        # Stable cache keys for cloud: hash the original gs://|s3://|azure-blob:// URI.
        hash_key = storage_filepath if is_cloud_storage_file and storage_filepath else target_url
        return _build_cache_path(cache_dir, hash_key, fname)

    cache_dir = cache_dir or get_cache_dir()
    current_filename = _filename_for(url, storage_filepath)
    current_filepath = _cache_path(url, current_filename)

    if os.path.exists(current_filepath):
        return current_filepath

    if not download_resources:
        return current_filepath

    headers = _build_headers(url, hostname, access_token)
    try:
        r = http_get(url, stream=True, headers=headers, verify=VERIFY_SSL)
        r.raise_for_status()
        target_url = url
        target_filepath = current_filepath
    except requests.exceptions.SSLError as e:
        logger.error(
            f"SSL error during requests.get('{url}'): {e}\n"
            f"Try to set VERIFY_SSL=False in environment variables to bypass SSL verification."
        )
        raise e
    except requests.exceptions.HTTPError as e:
        if not fallback_upload_url:
            raise e
        logger.info("Download failed for proxy URL, retrying legacy upload path: %s", fallback_upload_url)
        fb_filename = os.path.basename(urlparse(fallback_upload_url).path)
        fb_filepath = _cache_path(fallback_upload_url, fb_filename)
        if os.path.exists(fb_filepath):
            return fb_filepath
        fb_headers = _build_headers(fallback_upload_url, hostname, access_token)
        try:
            r = http_get(fallback_upload_url, stream=True, headers=fb_headers, verify=VERIFY_SSL)
            r.raise_for_status()
            target_url = fallback_upload_url
            target_filepath = fb_filepath
        except Exception:
            raise e
    with io.open(target_filepath, mode="wb") as fout:
        fout.write(r.content)
        logger.info(f"File downloaded to {target_filepath}")
    return target_filepath


@contextmanager
def get_temp_dir():
    dirpath = mkdtemp()
    yield dirpath
    shutil.rmtree(dirpath)


def get_all_files_from_dir(d):
    out = []
    for name in os.listdir(d):
        filepath = os.path.join(d, name)
        if os.path.isfile(filepath):
            out.append(filepath)
    return out


def get_base64_content(
    url,
    hostname=None,
    access_token=None,
    task_id=None,
):
    """This helper function is used to download a file and return its base64 representation without saving to filesystem.

    :param url: File URL to download, it can be a uploaded file, local storage, cloud storage file or just http(s) url
    :param hostname: Label Studio Hostname, it will be used for uploaded files, local storage files and cloud storage files
      if not provided, it will be taken from LABEL_STUDIO_URL env variable
    :param access_token: Label Studio access token, it will be used for uploaded files, local storage files and cloud storage files
      if not provided, it will be taken from LABEL_STUDIO_API_KEY env variable
    :param task_id: Label Studio Task ID, required for cloud storage files
      because the URL will be rebuilt to `{hostname}/tasks/{task_id}/presign/?fileuri={url}`

    :return: base64 encoded file content
    """
    # get environment variables
    hostname = (
        hostname
        or os.getenv("LABEL_STUDIO_URL", "")
        or os.getenv("LABEL_STUDIO_HOST", "")
    )
    access_token = (
        access_token
        or os.getenv("LABEL_STUDIO_API_KEY", "")
        or os.getenv("LABEL_STUDIO_ACCESS_TOKEN", "")
    )
    if "localhost" in hostname:
        logger.warning(
            f"Using `localhost` ({hostname}) in LABEL_STUDIO_URL, "
            f"`localhost` is not accessible inside of docker containers. "
            f"You can check your IP with utilities like `ifconfig` and set it as LABEL_STUDIO_URL."
        )
    if hostname and not (
        hostname.startswith("http://") or hostname.startswith("https://")
    ):
        raise ValueError(
            f"Invalid hostname in LABEL_STUDIO_URL: {hostname}. "
            "Please provide full URL starting with protocol (http:// or https://)."
        )

    # fix file upload url
    if url.startswith("upload") or url.startswith("/upload"):
        url = "/data" + ("" if url.startswith("/") else "/") + url

    is_uploaded_file = url.startswith("/data/upload")
    is_local_storage_file = url.startswith("/data/") and "?d=" in url
    is_cloud_storage_file = is_cloud_storage_uri(url)
    parsed_url = urlparse(url)
    query_params = parse_qs(parsed_url.query)
    storage_filepath = query_params.get("filepath", [None])[0]
    is_storage_data_file = parsed_url.path.startswith("/storage-data/uploaded") or parsed_url.path.startswith(
        "storage-data/uploaded"
    )

    # Local storage file: try to load locally
    if is_local_storage_file:
        filepath = resolve_local_storage_file(url)
        if filepath and os.path.exists(filepath):
            logger.debug(
                f"Local Storage file path exists locally, read content directly: {filepath}"
            )
            with open(filepath, "rb") as f:
                return base64.b64encode(f.read()).decode("utf-8")

    # Upload, Local Storage, Storage Proxy or Cloud Storage file
    if is_uploaded_file or is_local_storage_file or is_cloud_storage_file or is_storage_data_file:
        # hostname check
        if not hostname:
            raise FileNotFoundError(
                f"Can't resolve url, hostname not provided: {url}. "
                "You can set LABEL_STUDIO_URL environment variable to use it as a hostname."
            )
        # uploaded and local storage file
        elif is_uploaded_file or is_local_storage_file:
            url = concat_urls(hostname, url)
            logger.info("Resolving url using hostname [" + hostname + "]: " + url)
        # s3, gs, azure-blob file
        elif is_cloud_storage_file:
            if task_id is None:
                raise Exception(
                    "Label Studio Task ID is required for cloud storage files"
                )
            encoded_fileuri = encode_presign_fileuri(url)
            url = concat_urls(
                hostname, f"/tasks/{task_id}/presign/?fileuri={encoded_fileuri}"
            )
            logger.info(
                "Cloud storage file: Resolving url using hostname ["
                + hostname
                + "]: "
                + url
            )
        elif is_storage_data_file:
            resolved_path = parsed_url.path or "/storage-data/uploaded/"
            url = concat_urls(hostname, resolved_path)
            if parsed_url.query:
                url = url + "?" + parsed_url.query
            logger.info(
                "Storage proxy file: Resolving url using hostname ["
                + hostname
                + "]: "
                + url
            )

        # check access token
        if not access_token:
            raise FileNotFoundError(
                "To access uploaded and local storage files you have to "
                "set LABEL_STUDIO_API_KEY environment variable."
            )

    # Download the content but don't save to filesystem
    headers = _build_headers(url, hostname, access_token)

    fallback_upload_url = None
    if is_storage_data_file and storage_filepath:
        upload_path = storage_filepath
        if upload_path.startswith("data/"):
            upload_path = upload_path[len("data/") :]
        fallback_path = "/data/" + upload_path if not upload_path.startswith("data/") else "/" + upload_path
        if hostname:
            fallback_upload_url = concat_urls(hostname, fallback_path)

    try:
        r = http_get(url, headers=headers, verify=VERIFY_SSL)
        r.raise_for_status()
        return base64.b64encode(r.content).decode("utf-8")
    except requests.exceptions.SSLError as e:
        logger.error(
            f"SSL error during requests.get('{url}'): {e}\n"
            f"Try to set VERIFY_SSL=False in environment variables to bypass SSL verification."
        )
        raise e
    except requests.exceptions.HTTPError as e:
        if fallback_upload_url:
            logger.info(
                "Download failed for proxy URL, retrying legacy upload path for base64: %s",
                fallback_upload_url,
            )
            fb_headers = _build_headers(fallback_upload_url, hostname, access_token)
            try:
                r = http_get(fallback_upload_url, headers=fb_headers, verify=VERIFY_SSL)
                r.raise_for_status()
                return base64.b64encode(r.content).decode("utf-8")
            except Exception:
                raise e
        raise e