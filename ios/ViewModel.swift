//
//  ViewModel.swift
//  SherpaOnnxTts
//
//  Created by fangjun on 2023/11/23.
//

import Foundation

// Function to create the offline TTS wrapper
func createOfflineTts(modelId: String) -> SherpaOnnxOfflineTtsWrapper? {
    guard let bundlePath = Bundle.main.path(forResource: "models", ofType: nil) else {
        print("Kislaytts: Cannot find models directory in bundle")
        return nil
    }
    
    // Create paths similar to Android implementation
    let modelPath = (bundlePath as NSString).appendingPathComponent(modelId)
    let tokensPath = (bundlePath as NSString).appendingPathComponent("tokens.txt")
    let dataDirPath = (bundlePath as NSString).appendingPathComponent("espeak-ng-data")
    
    print("Kislaytts: Located model files")
    
    // Configure the VITS model
    let vitsConfig = sherpaOnnxOfflineTtsVitsModelConfig(
        model: modelPath,
        lexicon: "", // Assuming lexicon is not needed
        tokens: tokensPath,
        dataDir: dataDirPath
    )
    
    // Configure the overall model configuration
    let modelConfig = sherpaOnnxOfflineTtsModelConfig(vits: vitsConfig)
    var config = sherpaOnnxOfflineTtsConfig(model: modelConfig)
    print("Kislaytts: Successfully created config")
    
    // Initialize and return the TTS wrapper
    return SherpaOnnxOfflineTtsWrapper(config: &config)
}