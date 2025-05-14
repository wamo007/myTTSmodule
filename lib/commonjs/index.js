"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;
var _reactNative = require("react-native");
// TTSManager.js

const {
  TTSManager
} = _reactNative.NativeModules;
const ttsManagerEmitter = new _reactNative.NativeEventEmitter(TTSManager);
const initialize = modelId => {
  TTSManager.initializeTTS(22050, 1, modelId);
};
const generateAndPlay = async (text, sid, speed) => {
  try {
    const result = await TTSManager.generateAndPlay(text, sid, speed);
    console.log(result);
  } catch (error) {
    console.error(error);
  }
};
const deinitialize = () => {
  TTSManager.deinitialize();
};
const addVolumeListener = callback => {
  const subscription = ttsManagerEmitter.addListener('VolumeUpdate', event => {
    const {
      volume
    } = event;
    callback(volume);
  });
  return subscription;
};
var _default = exports.default = {
  initialize,
  generateAndPlay,
  deinitialize,
  addVolumeListener
};
//# sourceMappingURL=index.js.map