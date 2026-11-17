// Build editable vendored source, never download or copy a prebuilt editor bundle.
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '..');
const source = path.join(root, 'vendor/label-studio');
const mode = process.env.LS_EDITOR_MODE || 'production';
if (!['production', 'development'].includes(mode)) throw new Error('LS_EDITOR_MODE must be production or development');
const installed = path.join(source, 'node_modules');
const workspaceCache = path.resolve(
  root,
  '../.tools/label-studio-1.23.2/web/node_modules',
);
const deps =
  process.env.LS_DEPS_ROOT ||
  (fs.existsSync(installed) ? installed : workspaceCache);
if (!fs.existsSync(path.join(deps, 'webpack/package.json'))) {
  throw new Error(
    '缺少 LS 源码构建依赖。请按 vendor/label-studio/UPSTREAM.md 安装，或通过 LS_DEPS_ROOT 指定本地依赖目录。',
  );
}
const req = createRequire(path.join(deps, '../package.json'));
const webpack = req('webpack');
const TerserPlugin = req('terser-webpack-plugin');
const resolve = (name) => req.resolve(name);
const jiti = req('jiti')(__filename, {
  alias: {
    'tailwindcss-animate': resolve('tailwindcss-animate'),
    'tailwind-scrollbar': resolve('tailwind-scrollbar'),
  },
});
const tailwind = jiti(path.join(source, 'libs/ui/src/tailwind.config.js'));
tailwind.content = tailwind.content.map((pattern) =>
  path.resolve(source, pattern).replaceAll('\\', '/'),
);
tailwind.content.push(
  path
    .join(source, 'platform-config/**/*.{js,jsx,ts,tsx}')
    .replaceAll('\\', '/'),
);
const output = path.join(root, 'public/ls-editor');
const aliases = Object.fromEntries(
  ['editor', 'core', 'ui', 'app-common'].map((n) => [
    `@humansignal/${n}`,
    path.join(source, 'libs', n, 'src'),
  ]),
);
aliases['@humansignal/icons'] = path.join(source, 'libs/ui/src/assets/icons');
aliases['@humansignal/shad'] = path.join(source, 'libs/ui/src/shad');
aliases.react = path.join(deps, 'react');
aliases['react-dom'] = path.join(deps, 'react-dom');
const css = (modules) => [
  {
    loader: resolve('style-loader'),
    options: {
      insert: function collectEditorStyles(element) {
        window.__platformLsStyles = window.__platformLsStyles || [];
        window.__platformLsStyles.push(element);
      },
    },
  },
  { loader: resolve('css-loader'), options: { modules } },
];
const sass = {
  loader: resolve('sass-loader'),
  options: {
    implementation: req('sass'),
    sassOptions: {
      quietDeps: true,
      silenceDeprecations: [
        'legacy-js-api',
        'import',
        'global-builtin',
        'color-functions',
      ],
    },
  },
};
webpack(
  {
    mode,
    parallelism: 4,
    context: source,
    devtool: false,
    entry: path.join(root, 'src/ls-editor-runtime/index.js'),
    output: {
      path: output,
      filename: 'editor.[contenthash:12].js',
      publicPath: '/ls-editor/',
      clean: true,
      library: { name: 'PlatformLs', type: 'window' },
    },
    resolve: {
      extensions: ['.tsx', '.ts', '.jsx', '.js', '.json'],
      alias: aliases,
      modules: ['node_modules', deps],
      fallback: { fs: false, path: false, crypto: false },
    },
    resolveLoader: { modules: [deps] },
    module: {
      rules: [
        {
          test: /\.[jt]sx?$/,
          exclude: /node_modules/,
          resolve: { modules: [deps, 'node_modules'] },
          use: {
            loader: resolve('babel-loader'),
            options: {
              babelrc: false,
              configFile: false,
              presets: [
                [resolve('@babel/preset-env'), { targets: { chrome: '110' } }],
                [resolve('@babel/preset-react'), { runtime: 'automatic' }],
                resolve('@babel/preset-typescript'),
              ],
            },
          },
        },
        {
          test: /\.module\.s[ac]ss$/,
          use: [
            ...css({
              localIdentName: '[local]--[hash:base64:5]',
              namedExport: false,
            }),
            sass,
          ],
        },
        {
          test: /\.s[ac]ss$/,
          exclude: /\.module\.s[ac]ss$/,
          use: [...css({ mode: 'local', localIdentName: 'lsf-[local]' }), sass],
        },
        {
          test: /tailwind\.css$/,
          use: [
            ...css(false),
            {
              loader: resolve('postcss-loader'),
              options: {
                postcssOptions: {
                  config: false,
                  plugins: [
                    req('tailwindcss')(tailwind),
                    req('autoprefixer')(),
                  ],
                },
              },
            },
          ],
        },
        { test: /\.css$/, exclude: /tailwind\.css$/, use: css(false) },
        {
          test: /\.svg$/,
          use: [
            { loader: resolve('@svgr/webpack'), options: { ref: true } },
            resolve('url-loader'),
          ],
        },
        {
          test: /\.(png|jpe?g|gif|woff2?|ttf|mp3|wav|xml|wasm)$/,
          type: 'asset/resource',
        },
      ],
    },
    plugins: [
      new webpack.DefinePlugin({
        'process.env.CSS_PREFIX': JSON.stringify('lsf-'),
        'process.env.BUILD_NO_SERVER': 'true',
        'process.env.RELEASE_NAME': JSON.stringify('platform-ls-1.23.2'),
      }),
    ],
    optimization: { minimize: mode === 'production', minimizer: [new TerserPlugin({ parallel: false })] },
    performance: { hints: false },
  },
  (error, stats) => {
    if (error || stats.hasErrors()) {
      console.error(
        error ||
          stats.toString({ all: false, errors: true, errorDetails: false }),
      );
      process.exitCode = 1;
      return;
    }
    const entry = stats.toJson({ all: false, entrypoints: true }).entrypoints.main.assets.find(asset => asset.name.endsWith('.js')).name;
    const bytes = fs.readFileSync(path.join(output, entry));
    fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify({
      protocolVersion: 1, mode, entry,
      sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
      sourceVersion: '1.23.2',
    }, null, 2));
    console.log(stats.toString({ all: false, timings: true, assets: true }));
  },
);
