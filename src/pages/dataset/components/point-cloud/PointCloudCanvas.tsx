import { OrbitControls, PerspectiveCamera } from '@react-three/drei';
import { Canvas } from '@react-three/fiber';
import { Button, Descriptions, Spin } from 'antd';
import React from 'react';
import type * as THREE from 'three';
import type { PointCloudLoadResult } from './pointCloudUtils';

const PointCloudMesh: React.FC<{
  geometry: THREE.BufferGeometry;
  material: THREE.PointsMaterial;
}> = ({ geometry, material }) => (
  <points geometry={geometry} material={material} frustumCulled={false} />
);

export type PointCloudCanvasProps = {
  result: PointCloudLoadResult | null;
  loading: boolean;
  placeholder: string;
  controlsRef: React.RefObject<any>;
  onClearPreview: () => void;
};

const PointCloudCanvas: React.FC<PointCloudCanvasProps> = ({
  result,
  loading,
  placeholder,
  controlsRef,
  onClearPreview,
}) => {
  return (
    <div
      style={{
        position: 'relative',
        width: '100%',
        height: 'calc(100vh - 360px)',
        minHeight: 480,
        borderRadius: 12,
        overflow: 'hidden',
        border: '1px solid #f0f0f0',
      }}
    >
      <Canvas style={{ position: 'absolute', inset: 0 }}>
        <color attach="background" args={['#f5f5f5']} />
        <PerspectiveCamera makeDefault position={[2, 2, 2]} fov={60} />
        <ambientLight intensity={0.6} />
        <directionalLight position={[5, 10, 5]} intensity={1.0} />
        <axesHelper args={[1]} />
        <OrbitControls
          ref={controlsRef}
          makeDefault
          enableDamping
          dampingFactor={0.08}
        />
        {result?.geometry && result?.material && (
          <PointCloudMesh
            geometry={result.geometry}
            material={result.material}
          />
        )}
      </Canvas>

      <div
        style={{
          position: 'absolute',
          inset: 0,
          zIndex: 1,
          pointerEvents: 'none',
          background:
            'radial-gradient(ellipse at center, rgba(0,0,0,0.00) 0%, rgba(0,0,0,0.06) 70%, rgba(0,0,0,0.12) 100%)',
        }}
      />

      <div
        style={{
          position: 'absolute',
          top: 16,
          left: 16,
          zIndex: 10,
          width: 340,
          maxWidth: 'calc(100% - 32px)',
          padding: 12,
          borderRadius: 12,
          background: 'rgba(255,255,255,0.92)',
          backdropFilter: 'blur(10px)',
          boxShadow: '0 8px 24px rgba(0,0,0,0.1)',
          fontSize: 12,
          color: 'rgba(0,0,0,0.65)',
          lineHeight: 1.6,
        }}
      >
        <div style={{ fontWeight: 600, color: '#000', marginBottom: 4 }}>
          操作提示
        </div>
        左键旋转，滚轮缩放，右键平移；zip
        包需先在上方列表选择文件并点击「开始预览」。
      </div>

      {!result && !loading && (
        <div
          style={{
            position: 'absolute',
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            zIndex: 5,
            textAlign: 'center',
            color: 'rgba(0,0,0,0.45)',
          }}
        >
          {placeholder}
        </div>
      )}

      {result && (
        <div
          style={{
            position: 'absolute',
            left: 16,
            bottom: 16,
            zIndex: 10,
            width: 300,
            padding: 12,
            borderRadius: 12,
            background: 'rgba(255,255,255,0.92)',
            backdropFilter: 'blur(10px)',
            boxShadow: '0 8px 24px rgba(0,0,0,0.12)',
          }}
        >
          <Descriptions size="small" column={1}>
            <Descriptions.Item label="文件名">
              {result.fileName}
            </Descriptions.Item>
            <Descriptions.Item
              label={result.removedPointCount > 0 ? '有效点数' : '点数'}
            >
              {result.pointCount.toLocaleString()}
            </Descriptions.Item>
            {result.removedPointCount > 0 && (
              <>
                <Descriptions.Item label="原始点数">
                  {result.originalPointCount.toLocaleString()}
                </Descriptions.Item>
                <Descriptions.Item label="已过滤">
                  {result.removedPointCount.toLocaleString()}
                  <span style={{ color: 'rgba(0,0,0,0.45)', marginLeft: 4 }}>
                    （无效坐标 NaN）
                  </span>
                </Descriptions.Item>
              </>
            )}
          </Descriptions>
          <div style={{ marginTop: 12 }}>
            <Button size="small" onClick={onClearPreview}>
              清除预览
            </Button>
          </div>
        </div>
      )}

      {loading && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 20,
            background: 'rgba(255,255,255,0.5)',
          }}
        >
          <Spin size="large" tip="加载点云中…" />
        </div>
      )}
    </div>
  );
};

export default PointCloudCanvas;
