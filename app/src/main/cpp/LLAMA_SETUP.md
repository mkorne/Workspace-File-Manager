## llama.cpp Setup (Required for On-Device AI)

The On-Device Mini AI uses llama.cpp compiled as a native JNI library.
You must add the llama.cpp source tree before building:

```bash
# From inside NewProject/app/src/main/cpp/
git clone https://github.com/ggml-org/llama.cpp.git llama.cpp
```

Then get a GGUF model (Phi-3 Mini is ideal for Android — small, fast, capable):
```bash
# Download Phi-3-mini 4-bit quantised (≈2.4 GB) to your device:
# https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf

# Or TinyLlama (≈600 MB) for very low-RAM devices:
# https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF

# Push to device:
adb push Phi-3-mini-4k-instruct-q4.gguf /sdcard/models/model.gguf
```

Then in the app, go to **AI tab → Change path** and enter `/sdcard/models/model.gguf`, then tap **Load model**.