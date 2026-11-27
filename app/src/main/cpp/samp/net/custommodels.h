#pragma once

#include <cstdint>
#include <cstddef>
#include <string>

#include "../vendor/raknet/RakClientInterface.h"
#include "../vendor/raknet/NetworkTypes.h"

class CCustomModels
{
public:
    static constexpr uint8_t MODEL_TYPE_CHAR   = 1; // Custom skin (20001..30000)
    static constexpr uint8_t MODEL_TYPE_SIMPLE = 2; // Custom object (-1000..-30000)

    static constexpr uint8_t FILE_TYPE_DFF = 1;
    static constexpr uint8_t FILE_TYPE_TXD = 2;

    static constexpr int32_t MIN_CUSTOM_SKIN_ID   = 20001;
    static constexpr int32_t MAX_CUSTOM_SKIN_ID   = 30000;
    static constexpr int32_t MAX_CUSTOM_OBJECT_ID = -1000;
    static constexpr int32_t MIN_CUSTOM_OBJECT_ID = -30000;

    static void Init(const char* szHostOrIp, int iPort);
    static void Reset();
    static void Process(RakClientInterface* pRakClient);

    // Incoming 0.3.DL RPC handlers
    static void OnModelInfoRPC(RPCParameters* rpcParams);
    static void OnModelFileRPC(RPCParameters* rpcParams);
    static void OnSrcFinishedDownloadingRPC(RPCParameters* rpcParams);
    static void OnSetPlayerVirtualWorldRPC(RPCParameters* rpcParams);

    // Called from Java background HTTP worker thread via JNI
    static void OnHttpDownloadFinished(uint8_t fileType, uint32_t crc, bool success);

    // Model ID translation (server 0.3.DL ID -> loaded internal GTA slot < 20000, or baseId fallback)
    static int32_t ResolveModelId(int32_t serverModelId);
    static int32_t ResolveObjectModelId(int32_t serverModelId);
    static int32_t ResolvePedModelId(int32_t serverModelId);
    static int32_t ResolveTextDrawModelId(uint16_t rawWireModelId);

    // Slot & model queries
    static bool IsCustomSlot(int32_t internalSlot);
    static bool IsCustomServerModel(int32_t serverModelId);
    static bool IsCustomPedModel(int32_t serverModelId);
    static bool EnsureSlotLoaded(int32_t internalSlot);

    // IEEE 802.3 CRC32 utilities
    static uint32_t CalcCrc32(const uint8_t* data, size_t len);
    static bool CalcFileCrc32(const std::string& path, uint32_t& outCrc, uint32_t& outSize);
};
