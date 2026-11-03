import { OrbitControls, PerspectiveCamera } from '@react-three/drei';
import { Canvas, useThree } from '@react-three/fiber';
import { Alert, Spin } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import * as THREE from 'three';
import {
  getLeRobotPointCloud,
  type LeRobotPointCloud,
} from '@/services/datasetLeRobot';
import { getApiErrorMessage } from '@/utils/apiError';

type Props = {
  versionId: string;
  episodeIndex: number;
  frame: number;
  feature: string;
};

const CameraFit: React.FC<{ geometry: THREE.BufferGeometry }> = ({
  geometry,
}) => {
  const { camera } = useThree();
  const controlsRef = useRef<any>(null);

  useEffect(() => {
    geometry.computeBoundingBox();
    const box = geometry.boundingBox;
    if (!box) return;
    const center = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3());
    const radius = Math.max(size.x, size.y, size.z, 0.05);
    camera.position.set(
      center.x + radius * 2.3,
      center.y + radius * 1.7,
      center.z + radius * 2.3,
    );
    camera.near = Math.max(radius / 100, 0.0001);
    camera.far = Math.max(radius * 100, 10);
    camera.updateProjectionMatrix();
    controlsRef.current?.target.copy(center);
    controlsRef.current?.update();
  }, [camera, geometry]);

  return (
    <OrbitControls
      ref={controlsRef}
      makeDefault
      enableDamping
      dampingFactor={0.08}
    />
  );
};

const LeRobotPointCloudView: React.FC<Props> = ({
  versionId,
  episodeIndex,
  frame,
  feature,
}) => {
  const [data, setData] = useState<LeRobotPointCloud>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(undefined);
    setData(undefined);
    getLeRobotPointCloud(versionId, episodeIndex, feature)
      .then((value) => !cancelled && setData(value))
      .catch((reason) => !cancelled && setError(getApiErrorMessage(reason)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [episodeIndex, feature, versionId]);

  const frameOffset = useMemo(() => {
    if (!data?.frameIndices.length) return 0;
    const exact = data.frameIndices.indexOf(frame);
    return exact >= 0
      ? exact
      : Math.max(0, Math.min(frame, data.frames.length - 1));
  }, [data, frame]);
  const points = data?.frames[frameOffset] ?? [];
  const geometry = useMemo(() => {
    const value = new THREE.BufferGeometry();
    const positions = new Float32Array(points.length * 3);
    const colors =
      data?.dimensions === 6 ? new Float32Array(points.length * 3) : null;
    points.forEach((point, index) => {
      positions.set(point.slice(0, 3), index * 3);
      if (colors) {
        const scale =
          Math.max(point[3] ?? 0, point[4] ?? 0, point[5] ?? 0) > 1 ? 255 : 1;
        colors.set(
          [
            (point[3] ?? 0) / scale,
            (point[4] ?? 0) / scale,
            (point[5] ?? 0) / scale,
          ],
          index * 3,
        );
      }
    });
    value.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    if (colors)
      value.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    value.computeBoundingBox();
    value.computeBoundingSphere();
    return value;
  }, [data?.dimensions, points]);

  useEffect(() => () => geometry.dispose(), [geometry]);

  if (loading)
    return (
      <div className="lerobot-pointcloud lerobot-pointcloud-state">
        <Spin size="large" tip="正在加载真实点云..." />
      </div>
    );
  if (error)
    return (
      <div className="lerobot-pointcloud lerobot-pointcloud-state">
        <Alert
          type="error"
          showIcon
          message="点云加载失败"
          description={error}
        />
      </div>
    );
  if (!data || !points.length)
    return (
      <div className="lerobot-pointcloud lerobot-pointcloud-state">
        当前帧没有点云数据
      </div>
    );

  const radius = Math.max(geometry.boundingSphere?.radius ?? 0.05, 0.05);
  return (
    <div className="lerobot-pointcloud">
      <Canvas dpr={[1, 1.5]}>
        <color attach="background" args={['#090b0c']} />
        <PerspectiveCamera makeDefault fov={52} />
        <gridHelper args={[radius * 6, 16, '#384148', '#20262a']} />
        <axesHelper args={[radius]} />
        <points geometry={geometry} frustumCulled={false}>
          <pointsMaterial
            size={Math.max(radius * 0.08, 0.004)}
            color={data.dimensions === 6 ? undefined : '#55d6a0'}
            vertexColors={data.dimensions === 6}
            sizeAttenuation
          />
        </points>
        <CameraFit geometry={geometry} />
      </Canvas>
      <div className="lerobot-pointcloud-meta">
        <strong>{data.featureKey}</strong>
        <span>{points.length.toLocaleString()} points</span>
        <span>Frame {data.frameIndices[frameOffset] ?? frame}</span>
      </div>
    </div>
  );
};

export default LeRobotPointCloudView;
