import { Capacitor, registerPlugin } from '@capacitor/core';

if (Capacitor.isNativePlatform()) {
  const NativeImages = registerPlugin('NativeImages');
  window.nativeImageApp = {
    saveImage: options => NativeImages.saveImage(options),
    shareImage: options => NativeImages.shareImage(options)
  };
  document.getElementById('dlMerge').textContent = '保存拼接图到相册';
  document.getElementById('dlText').textContent = '保存文字图到相册';
  document.getElementById('fontFamily').value = 'sans-serif';
  document.querySelectorAll('[data-native-only]').forEach(element => {
    element.hidden = false;
  });
  document.getElementById('uploadArea').textContent = '点击选择手机里的图片';
  window.dispatchEvent(new Event('image-tool-native-ready'));
}
