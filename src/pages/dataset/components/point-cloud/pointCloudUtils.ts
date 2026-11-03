import * as THREE from 'three';
import { PCDLoader } from 'three/examples/jsm/loaders/PCDLoader.js';
import { PLYLoader } from 'three/examples/jsm/loaders/PLYLoader.js';
import { PointCloudFileFormat } from '@/services/pointcloud';

export type PointCloudLoadResult = {
  fileName: string;
  geometry: THREE.BufferGeometry;
  material: THREE.PointsMaterial;
  /** 当前渲染的有效点数 */
  pointCount: number;
  /** 过滤前原始点数；无过滤时与 pointCount 相同 */
  originalPointCount: number;
  /** 被过滤的无效点数 */
  removedPointCount: number;
};

export type FilterInvalidPointsResult = {
  total: number;
  valid: number;
  removed: number;
};

/** 过滤 position 为 NaN/Infinity 的点，并同步 color 等属性 */
export function filterInvalidPoints(
  geometry: THREE.BufferGeometry,
): FilterInvalidPointsResult {
  const position = geometry.getAttribute('position') as
    | THREE.BufferAttribute
    | undefined;
  if (!position) {
    return { total: 0, valid: 0, removed: 0 };
  }

  const total = position.count;
  const validIndices: number[] = [];

  for (let i = 0; i < total; i++) {
    const x = position.getX(i);
    const y = position.getY(i);
    const z = position.getZ(i);
    if (Number.isFinite(x) && Number.isFinite(y) && Number.isFinite(z)) {
      validIndices.push(i);
    }
  }

  const valid = validIndices.length;
  const removed = total - valid;

  if (removed === 0) {
    return { total, valid, removed };
  }

  if (valid === 0) {
    throw new Error(`点云全部为无效坐标（NaN/Infinity），共 ${total} 点`);
  }

  const filterAttribute = (attr: THREE.BufferAttribute) => {
    const itemSize = attr.itemSize;
    const ArrayCtor = attr.array.constructor as Float32ArrayConstructor;
    const filtered = new ArrayCtor(valid * itemSize);
    for (let j = 0; j < valid; j++) {
      const src = validIndices[j];
      for (let k = 0; k < itemSize; k++) {
        filtered[j * itemSize + k] = attr.array[src * itemSize + k] as number;
      }
    }
    return new THREE.BufferAttribute(filtered, itemSize);
  };

  geometry.setAttribute('position', filterAttribute(position));

  const color = geometry.getAttribute('color') as THREE.BufferAttribute | null;
  if (color) {
    geometry.setAttribute('color', filterAttribute(color));
  }

  const normal = geometry.getAttribute(
    'normal',
  ) as THREE.BufferAttribute | null;
  if (normal) {
    geometry.setAttribute('normal', filterAttribute(normal));
  }

  geometry.computeBoundingBox();
  geometry.computeBoundingSphere();

  return { total, valid, removed };
}

export function makePointsMaterial(geometry: THREE.BufferGeometry) {
  const hasColors = !!geometry.getAttribute('color');
  return new THREE.PointsMaterial({
    size: 0.02,
    color: hasColors ? undefined : new THREE.Color('#1890ff'),
    vertexColors: hasColors,
    sizeAttenuation: true,
  });
}

/** PCD 常用相机/光学坐标系，Three.js 为 Y-up（与官方 PCD 示例 rotateX(π) 一致） */
export function applyPcdCoordinateSystem(geometry: THREE.BufferGeometry) {
  geometry.rotateX(Math.PI);
}

export function normalizeGeometry(
  geometry: THREE.BufferGeometry,
  options?: { pcdCoordinateFix?: boolean },
) {
  geometry.computeBoundingBox();
  const box = geometry.boundingBox;
  if (!box) return { radius: 1 };

  const center = new THREE.Vector3();
  box.getCenter(center);

  const size = new THREE.Vector3();
  box.getSize(size);

  geometry.translate(-center.x, -center.y, -center.z);

  if (options?.pcdCoordinateFix) {
    applyPcdCoordinateSystem(geometry);
    geometry.computeBoundingBox();
    geometry.boundingBox?.getSize(size);
  }

  const radius = Math.max(size.x, size.y, size.z) / 2 || 1;
  return { radius };
}

export function parsePointCloudBuffer(
  format: PointCloudFileFormat,
  arrayBuffer: ArrayBuffer,
): THREE.BufferGeometry {
  if (format === PointCloudFileFormat.PLY) {
    const loader = new PLYLoader();
    return loader.parse(arrayBuffer);
  }
  const loader = new PCDLoader();
  const parsed: unknown = (
    loader as { parse: (buf: ArrayBuffer, path: string) => unknown }
  ).parse(arrayBuffer, '');
  if (parsed && (parsed as THREE.Points).isPoints) {
    return (parsed as THREE.Points).geometry as THREE.BufferGeometry;
  }
  if (parsed && (parsed as THREE.BufferGeometry).isBufferGeometry) {
    return parsed as THREE.BufferGeometry;
  }
  throw new Error('PCD 文件解析失败');
}
