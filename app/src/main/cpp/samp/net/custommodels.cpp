#include "custommodels.h"

#include "../main.h"
#include "../game/game.h"
#include "../game/Streaming.h"
#include "../game/Models/ModelInfo.h"
#include "../game/Models/PedModelInfo.h"
#include "../game/Models/AtomicModelInfo.h"
#include "../game/RW/RenderWare.h"
#include "../game/RW/stream.h"
#include "../vendor/armhook/patch.h"
#include "../vendor/raknet/BitStream.h"
#include "../vendor/raknet/SAMP/SAMPRPC.h"
#include "../java/jniutil.h"
#include "../gui/gui.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <deque>
#include <dlfcn.h>
#include <mutex>
#include <sys/stat.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>

extern UI* pUI;
extern CJavaWrapper* pJavaWrapper;
extern char* g_pszStorage;

namespace
{
// ============================================================================
// Constants & Limits
// ============================================================================
constexpr size_t   kMaxModelFileSizeBytes   = 64u * 1024u * 1024u; // 64 MB safety cap
constexpr size_t   kMaxConcurrentRequests   = 3;
constexpr uint32_t kDownloadTimeoutMs       = 25000;
constexpr uint32_t kBatchIdleGraceMs        = 600;
constexpr int      kMaxRetriesPerFile       = 1;
constexpr int      kMaxEngineLoadsPerFrame  = 4;
constexpr int32_t  kFallbackObjectModel     = 18631;
constexpr int32_t  kFallbackPedModel        = 0;
constexpr int32_t  kMinDynamicSlot          = 1000;
constexpr int32_t  kMaxDynamicSlot          = 19999;

// RenderWare chunk IDs
constexpr uint32_t kRwIdStruct          = 0x0001;
constexpr uint32_t kRwIdTextureNative   = 0x0015;
constexpr uint32_t kRwIdTexDictionary   = 0x0016;
constexpr uint32_t kRwIdNodeName        = 0x0253F2FEu;

// RenderWare D3D platform IDs
constexpr uint32_t kRwPlatformD3D8      = 8;
constexpr uint32_t kRwPlatformD3D9      = 9;

// D3D FourCC codes (little-endian)
constexpr uint32_t kFourCcDxt1          = 0x31545844u; // 'DXT1'
constexpr uint32_t kFourCcDxt3          = 0x33545844u; // 'DXT3'
constexpr uint32_t kFourCcDxt5          = 0x35545844u; // 'DXT5'

// ============================================================================
// Internal Data Structures
// ============================================================================
struct FileKey
{
    uint8_t  type; // FILE_TYPE_DFF or FILE_TYPE_TXD
    uint32_t crc;

    bool operator==(const FileKey& o) const noexcept
    {
        return type == o.type && crc == o.crc;
    }
};

struct FileKeyHash
{
    size_t operator()(const FileKey& k) const noexcept
    {
        return (static_cast<size_t>(k.type) * 2654435761u) ^ static_cast<size_t>(k.crc);
    }
};

enum class FileState : uint8_t
{
    Pending = 0,
    RequestedRakNet,
    DownloadingHttp,
    Ready,
    Failed
};

struct FileTask
{
    uint8_t  type = 0;
    uint32_t crc = 0;
    uint32_t expectedSize = 0;
    FileState state = FileState::Pending;
    uint32_t requestSentTick = 0;
    int      retries = 0;
    std::string cachePath;
    std::vector<uint8_t> rakNetBuffer; // For direct RakNet transfer (arg == 1)
};

enum class ModelLoadState : uint8_t
{
    WaitingFiles = 0,
    ReadyToLoad,
    Loaded,
    Failed
};

struct CustomModelEntry
{
    uint8_t  type = 0;         // MODEL_TYPE_CHAR (1) or MODEL_TYPE_SIMPLE (2)
    int32_t  virtualWorld = -1;
    int32_t  baseId = 0;
    int32_t  newId = 0;        // Server 0.3.DL ID (20001..30000 or -1000..-30000)
    uint32_t dffCrc = 0;
    uint32_t txdCrc = 0;
    uint32_t dffSize = 0;
    uint32_t txdSize = 0;
    uint8_t  timeOn = 0;
    uint8_t  timeOff = 0;

    int32_t  internalSlot = -1; // Allocated GTA SA slot < 20000
    ModelLoadState loadState = ModelLoadState::WaitingFiles;
};

struct HttpResult
{
    uint8_t  fileType;
    uint32_t crc;
    bool     success;
};

struct DecodedTxdCache
{
    std::unordered_map<std::string, RwTexture*> textures; // lowercase name -> RwTexture* (ref owned by cache)
    bool triedLoad = false;
};

// ============================================================================
// Static Module State (Main Thread except s_httpMutex / s_httpResults)
// ============================================================================
std::string s_cacheDir;
bool s_initialized = false;
int32_t s_currentVirtualWorld = 0;

std::unordered_map<int32_t, CustomModelEntry> s_modelsByServerId;
std::unordered_map<int32_t, int32_t>          s_serverIdBySlot; // internalSlot -> serverModelId
std::unordered_set<int32_t>                   s_customSlots;    // all slots ever allocated to custom models

// Reusable allocated CModelInfo slots so GMX/reconnect never exhausts bump-allocated CStore pools
std::vector<int32_t> s_reusablePedSlots;
std::vector<int32_t> s_reusableObjSlots;
int32_t s_nextScanSlot = kMaxDynamicSlot;

// Download & TXD caches
std::unordered_map<FileKey, FileTask, FileKeyHash> s_files;
std::deque<FileKey> s_downloadQueue;
std::unordered_map<uint32_t, DecodedTxdCache> s_txdCache; // txdCrc -> decoded RwTexture map

// Batch completion tracking
int32_t  s_expectedBatchCount = 0;
int32_t  s_receivedBatchCount = 0;
uint32_t s_lastModelInfoTick  = 0;
bool     s_batchActive        = false;
bool     s_finishedSent       = false;
uint32_t s_lastProgressChatTick = 0;

// Thread-safe HTTP completion queue
std::mutex s_httpMutex;
std::vector<HttpResult> s_httpResults;

// Temporary texture-find interceptor state (main thread only)
const std::unordered_map<std::string, RwTexture*>* s_activeTxdMap = nullptr;
RwTextureCallBackFind s_prevFindCallback = nullptr;

// ============================================================================
// Utilities: String, Path, File I/O, CRC32
// ============================================================================
std::string ToLowerAscii(const char* str)
{
    if (!str) return {};
    std::string out;
    out.reserve(strlen(str));
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(str); *p; ++p)
    {
        out.push_back(static_cast<char>(std::tolower(*p)));
    }
    return out;
}

std::string SanitizeHostDir(const char* host, int port)
{
    std::string clean;
    if (host)
    {
        for (const char* p = host; *p && clean.size() < 64; ++p)
        {
            unsigned char c = static_cast<unsigned char>(*p);
            if (std::isalnum(c) || c == '.' || c == '-' || c == '_')
                clean.push_back(static_cast<char>(c));
            else
                clean.push_back('_');
        }
    }
    if (clean.empty()) clean = "server";
    char buf[96];
    snprintf(buf, sizeof(buf), "%s_%d", clean.c_str(), port);
    return std::string(buf);
}

void EnsureDirectoryTree(const std::string& dirPath)
{
    if (dirPath.empty()) return;
    std::string cur;
    cur.reserve(dirPath.size());
    for (size_t i = 0; i < dirPath.size(); ++i)
    {
        char c = dirPath[i];
        cur.push_back(c);
        if (c == '/' && cur.size() > 1)
        {
            mkdir(cur.c_str(), 0755);
        }
    }
    mkdir(cur.c_str(), 0755);
}

std::string BuildCachePath(uint8_t fileType, uint32_t crc)
{
    char fname[48];
    snprintf(fname, sizeof(fname), "%08X.%s",
             crc,
             (fileType == CCustomModels::FILE_TYPE_TXD) ? "txd" : "dff");
    return s_cacheDir + "/" + fname;
}

bool ReadWholeFile(const std::string& path, std::vector<uint8_t>& out, size_t maxBytes)
{
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return false;

    if (fseek(fp, 0, SEEK_END) != 0)
    {
        fclose(fp);
        return false;
    }
    long sz = ftell(fp);
    if (sz <= 0 || static_cast<size_t>(sz) > maxBytes)
    {
        fclose(fp);
        return false;
    }
    if (fseek(fp, 0, SEEK_SET) != 0)
    {
        fclose(fp);
        return false;
    }

    out.resize(static_cast<size_t>(sz));
    size_t nRead = fread(out.data(), 1, out.size(), fp);
    fclose(fp);
    return nRead == out.size();
}

bool WriteFileAtomic(const std::string& path, const uint8_t* data, size_t len)
{
    if (!data || len == 0 || len > kMaxModelFileSizeBytes) return false;
    EnsureDirectoryTree(s_cacheDir);

    std::string tmpPath = path + ".tmp";
    FILE* fp = fopen(tmpPath.c_str(), "wb");
    if (!fp) return false;

    size_t written = fwrite(data, 1, len, fp);
    fflush(fp);
    fclose(fp);

    if (written != len)
    {
        remove(tmpPath.c_str());
        return false;
    }
    remove(path.c_str());
    if (rename(tmpPath.c_str(), path.c_str()) != 0)
    {
        remove(tmpPath.c_str());
        return false;
    }
    return true;
}

uint32_t ReadU32LE(const uint8_t* p)
{
    return static_cast<uint32_t>(p[0])
         | (static_cast<uint32_t>(p[1]) << 8)
         | (static_cast<uint32_t>(p[2]) << 16)
         | (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t ReadU16LE(const uint8_t* p)
{
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

const uint32_t* GetCrc32Table()
{
    static uint32_t table[256];
    static bool ready = false;
    if (!ready)
    {
        for (uint32_t i = 0; i < 256; ++i)
        {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k)
                c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            table[i] = c;
        }
        ready = true;
    }
    return table;
}

// ============================================================================
// Software DXT1 / DXT3 / DXT5 + Uncompressed PC RenderWare TXD Decoder
// Decodes PC D3D8/D3D9 textures into RGBA8888 for Android GLES RwRaster
// ============================================================================
inline void UnpackRgb565(uint16_t c, uint8_t& r, uint8_t& g, uint8_t& b)
{
    uint8_t r5 = static_cast<uint8_t>((c >> 11) & 0x1F);
    uint8_t g6 = static_cast<uint8_t>((c >> 5) & 0x3F);
    uint8_t b5 = static_cast<uint8_t>(c & 0x1F);
    r = static_cast<uint8_t>((r5 << 3) | (r5 >> 2));
    g = static_cast<uint8_t>((g6 << 2) | (g6 >> 4));
    b = static_cast<uint8_t>((b5 << 3) | (b5 >> 2));
}

void DecodeDxtColorBlock(const uint8_t* block, uint8_t outRgba[16][4], bool allowTransparent)
{
    uint16_t c0 = ReadU16LE(block + 0);
    uint16_t c1 = ReadU16LE(block + 2);
    uint32_t bits = ReadU32LE(block + 4);

    uint8_t pal[4][4];
    UnpackRgb565(c0, pal[0][0], pal[0][1], pal[0][2]); pal[0][3] = 255;
    UnpackRgb565(c1, pal[1][0], pal[1][1], pal[1][2]); pal[1][3] = 255;

    if (!allowTransparent || c0 > c1)
    {
        for (int ch = 0; ch < 3; ++ch)
        {
            pal[2][ch] = static_cast<uint8_t>((2 * pal[0][ch] + pal[1][ch] + 1) / 3);
            pal[3][ch] = static_cast<uint8_t>((pal[0][ch] + 2 * pal[1][ch] + 1) / 3);
        }
        pal[2][3] = 255;
        pal[3][3] = 255;
    }
    else
    {
        for (int ch = 0; ch < 3; ++ch)
        {
            pal[2][ch] = static_cast<uint8_t>((pal[0][ch] + pal[1][ch]) / 2);
            pal[3][ch] = 0;
        }
        pal[2][3] = 255;
        pal[3][3] = 0;
    }

    for (int i = 0; i < 16; ++i)
    {
        uint32_t idx = (bits >> (2 * i)) & 0x3u;
        outRgba[i][0] = pal[idx][0];
        outRgba[i][1] = pal[idx][1];
        outRgba[i][2] = pal[idx][2];
        outRgba[i][3] = pal[idx][3];
    }
}

bool DecodeDxtImage(const uint8_t* src, size_t srcLen, int width, int height, int dxtMode, std::vector<uint8_t>& outRgba)
{
    int blocksX = (width + 3) / 4;
    int blocksY = (height + 3) / 4;
    size_t blockBytes = (dxtMode == 1) ? 8u : 16u;
    size_t needed = static_cast<size_t>(blocksX) * static_cast<size_t>(blocksY) * blockBytes;
    if (srcLen < needed) return false;

    outRgba.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 4u);

    const uint8_t* ptr = src;
    for (int by = 0; by < blocksY; ++by)
    {
        for (int bx = 0; bx < blocksX; ++bx)
        {
            uint8_t blk[16][4];
            if (dxtMode == 1)
            {
                DecodeDxtColorBlock(ptr, blk, true);
                ptr += 8;
            }
            else if (dxtMode == 3)
            {
                const uint8_t* alphaPtr = ptr;
                DecodeDxtColorBlock(ptr + 8, blk, false);
                for (int i = 0; i < 16; ++i)
                {
                    uint8_t b = alphaPtr[i >> 1];
                    uint8_t a4 = (i & 1) ? ((b >> 4) & 0x0Fu) : (b & 0x0Fu);
                    blk[i][3] = static_cast<uint8_t>((a4 << 4) | a4);
                }
                ptr += 16;
            }
            else // DXT5
            {
                uint8_t a0 = ptr[0];
                uint8_t a1 = ptr[1];
                uint8_t aPal[8];
                aPal[0] = a0;
                aPal[1] = a1;
                if (a0 > a1)
                {
                    aPal[2] = static_cast<uint8_t>((6 * a0 + 1 * a1 + 3) / 7);
                    aPal[3] = static_cast<uint8_t>((5 * a0 + 2 * a1 + 3) / 7);
                    aPal[4] = static_cast<uint8_t>((4 * a0 + 3 * a1 + 3) / 7);
                    aPal[5] = static_cast<uint8_t>((3 * a0 + 4 * a1 + 3) / 7);
                    aPal[6] = static_cast<uint8_t>((2 * a0 + 5 * a1 + 3) / 7);
                    aPal[7] = static_cast<uint8_t>((1 * a0 + 6 * a1 + 3) / 7);
                }
                else
                {
                    aPal[2] = static_cast<uint8_t>((4 * a0 + 1 * a1 + 2) / 5);
                    aPal[3] = static_cast<uint8_t>((3 * a0 + 2 * a1 + 2) / 5);
                    aPal[4] = static_cast<uint8_t>((2 * a0 + 3 * a1 + 2) / 5);
                    aPal[5] = static_cast<uint8_t>((1 * a0 + 4 * a1 + 2) / 5);
                    aPal[6] = 0;
                    aPal[7] = 255;
                }

                uint64_t aBits = 0;
                for (int k = 0; k < 6; ++k)
                {
                    aBits |= (static_cast<uint64_t>(ptr[2 + k]) << (8 * k));
                }

                DecodeDxtColorBlock(ptr + 8, blk, false);
                for (int i = 0; i < 16; ++i)
                {
                    uint32_t aIdx = static_cast<uint32_t>((aBits >> (3 * i)) & 0x7u);
                    blk[i][3] = aPal[aIdx];
                }
                ptr += 16;
            }

            for (int py = 0; py < 4; ++py)
            {
                int y = by * 4 + py;
                if (y >= height) continue;
                for (int px = 0; px < 4; ++px)
                {
                    int x = bx * 4 + px;
                    if (x >= width) continue;
                    size_t dstOff = (static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) * 4u;
                    const uint8_t* p = blk[py * 4 + px];
                    outRgba[dstOff + 0] = p[0];
                    outRgba[dstOff + 1] = p[1];
                    outRgba[dstOff + 2] = p[2];
                    outRgba[dstOff + 3] = p[3];
                }
            }
        }
    }
    return true;
}

RwTexture* CreateRwTextureFromRgba(const char* texName, const char* maskName, int width, int height,
                                   const std::vector<uint8_t>& rgba, uint32_t filterFlags)
{
    if (!texName || !texName[0] || width <= 0 || height <= 0 || width > 4096 || height > 4096)
        return nullptr;
    if (rgba.size() < static_cast<size_t>(width) * static_cast<size_t>(height) * 4u)
        return nullptr;

    const uint8_t* srcPixels = rgba.data();
    int curW = width;
    int curH = height;
    std::vector<uint8_t> scaled;
#if VER_x32
    // Cap decoded 0.3.DL RGBA textures to 512x512 on 32-bit Android to prevent VRAM OOM on 2 GB devices.
    constexpr int kMaxDim = 512;
    while (curW > kMaxDim || curH > kMaxDim)
    {
        int nextW = std::max(1, curW / 2);
        int nextH = std::max(1, curH / 2);
        std::vector<uint8_t> tmp(static_cast<size_t>(nextW) * static_cast<size_t>(nextH) * 4u);
        for (int y = 0; y < nextH; ++y)
        {
            int sy0 = y * 2;
            int sy1 = std::min(sy0 + 1, curH - 1);
            for (int x = 0; x < nextW; ++x)
            {
                int sx0 = x * 2;
                int sx1 = std::min(sx0 + 1, curW - 1);
                const uint8_t* p00 = srcPixels + (static_cast<size_t>(sy0) * curW + sx0) * 4u;
                const uint8_t* p10 = srcPixels + (static_cast<size_t>(sy0) * curW + sx1) * 4u;
                const uint8_t* p01 = srcPixels + (static_cast<size_t>(sy1) * curW + sx0) * 4u;
                const uint8_t* p11 = srcPixels + (static_cast<size_t>(sy1) * curW + sx1) * 4u;
                uint8_t* dst = tmp.data() + (static_cast<size_t>(y) * nextW + x) * 4u;
                for (int c = 0; c < 4; ++c)
                {
                    dst[c] = static_cast<uint8_t>((static_cast<unsigned>(p00[c]) + p10[c] + p01[c] + p11[c] + 2u) >> 2);
                }
            }
        }
        scaled.swap(tmp);
        srcPixels = scaled.data();
        curW = nextW;
        curH = nextH;
    }
#endif

    RwImage* img = RwImageCreate(curW, curH, 32);
    if (!img) return nullptr;
    if (!RwImageAllocatePixels(img) || !img->cpPixels)
    {
        RwImageDestroy(img);
        return nullptr;
    }

    size_t rowBytes = static_cast<size_t>(curW) * 4u;
    if (img->stride < static_cast<RwInt32>(rowBytes))
    {
        RwImageDestroy(img);
        return nullptr;
    }
    for (int y = 0; y < curH; ++y)
    {
        memcpy(img->cpPixels + static_cast<size_t>(y) * static_cast<size_t>(img->stride),
               srcPixels + static_cast<size_t>(y) * rowBytes,
               rowBytes);
    }

    RwInt32 rw = 0, rh = 0, rd = 0, rflags = 0;
    RwImageFindRasterFormat(img, rwRASTERTYPETEXTURE, &rw, &rh, &rd, &rflags);
    // Only mip level 0 is uploaded; strip mipmap flags so PowerVR/GLES2 never generates or samples broken mip chains.
    rflags &= ~(rwRASTERFORMATMIPMAP | rwRASTERFORMATAUTOMIPMAP);
    if (rw <= 0 || rh <= 0)
    {
        RwImageDestroy(img);
        return nullptr;
    }
    RwRaster* raster = RwRasterCreate(rw, rh, rd, rflags);
    if (!raster)
    {
        RwImageDestroy(img);
        return nullptr;
    }

    RwRaster* uploaded = RwRasterSetFromImage(raster, img);
    RwImageDestroy(img);
    if (!uploaded)
    {
        RwRasterDestroy(raster);
        return nullptr;
    }

    RwTexture* tex = RwTextureCreate(uploaded);
    if (!tex)
    {
        RwRasterDestroy(uploaded);
        return nullptr;
    }

    RwTextureSetName(tex, texName);
    if (maskName && maskName[0])
    {
        strncpy(tex->mask, maskName, rwTEXTUREBASENAMELENGTH - 1);
        tex->mask[rwTEXTUREBASENAMELENGTH - 1] = '\0';
    }

    uint32_t filterMode = filterFlags & 0xFFu;
    // CreateRwTextureFromRgba only uploads mip level 0; clamp mipmap filters so GLES/PowerVR never samples incomplete mipmaps as black.
    if (filterMode == rwFILTERNEAREST || filterMode == rwFILTERMIPNEAREST)
        filterMode = rwFILTERNEAREST;
    else
        filterMode = rwFILTERLINEAR;
    uint32_t addrU = (filterFlags >> 8) & 0x0Fu;
    uint32_t addrV = (filterFlags >> 12) & 0x0Fu;
    if (addrU == 0 || addrU > rwTEXTUREADDRESSBORDER) addrU = rwTEXTUREADDRESSWRAP;
    if (addrV == 0 || addrV > rwTEXTUREADDRESSBORDER) addrV = rwTEXTUREADDRESSWRAP;

    RwTextureSetFilterMode(tex, static_cast<RwTextureFilterMode>(filterMode));
    RwTextureSetAddressingU(tex, static_cast<RwTextureAddressMode>(addrU));
    RwTextureSetAddressingV(tex, static_cast<RwTextureAddressMode>(addrV));
    return tex;
}

bool DecodePcTextureNative(const uint8_t* structData, size_t structSize, RwTexture*& outTex, std::string& outNameLower)
{
    outTex = nullptr;
    outNameLower.clear();

    if (structSize < 88) return false;

    uint32_t platformId = ReadU32LE(structData + 0);
    if (platformId != kRwPlatformD3D8 && platformId != kRwPlatformD3D9)
        return false;

    uint32_t filterFlags = ReadU32LE(structData + 4);

    char nameBuf[33] = {};
    char maskBuf[33] = {};
    memcpy(nameBuf, structData + 8, 32);
    memcpy(maskBuf, structData + 40, 32);
    if (nameBuf[0] == '\0') return false;

    uint32_t rasterFormat = ReadU32LE(structData + 72);
    uint32_t d3dOrAlpha   = ReadU32LE(structData + 76);
    uint16_t width        = ReadU16LE(structData + 80);
    uint16_t height       = ReadU16LE(structData + 82);
    uint8_t  depth        = structData[84];
    // uint8_t numLevels  = structData[85];
    // uint8_t rasterType = structData[86];
    uint8_t  flagsOrComp  = structData[87];

    if (width == 0 || height == 0 || width > 4096 || height > 4096)
        return false;

    bool hasAlpha = (platformId == kRwPlatformD3D9)
        ? ((flagsOrComp & 0x01u) != 0)
        : (d3dOrAlpha != 0);

    size_t pos = 88;
    const uint8_t* palette = nullptr;
    bool isPal8 = (rasterFormat & rwRASTERFORMATPAL8) != 0;
    bool isPal4 = (rasterFormat & rwRASTERFORMATPAL4) != 0;

    if (isPal8)
    {
        if (pos + 1024 > structSize) return false;
        palette = structData + pos;
        pos += 1024;
    }
    else if (isPal4)
    {
        // RW 3.6 D3D8/D3D9 PAL4 stores 32 RGBA entries (128 bytes); guard older 64-byte tables
        size_t palBytes = (pos + 128 + 4 <= structSize) ? 128u : 64u;
        if (pos + palBytes > structSize) return false;
        palette = structData + pos;
        pos += palBytes;
    }

    if (pos + 4 > structSize) return false;
    uint32_t mip0Size = ReadU32LE(structData + pos);
    pos += 4;
    if (mip0Size == 0 || pos + mip0Size > structSize) return false;

    const uint8_t* pixelData = structData + pos;
    std::vector<uint8_t> rgba;

    int dxtMode = 0;
    if (platformId == kRwPlatformD3D9)
    {
        if (d3dOrAlpha == kFourCcDxt1) dxtMode = 1;
        else if (d3dOrAlpha == kFourCcDxt3) dxtMode = 3;
        else if (d3dOrAlpha == kFourCcDxt5) dxtMode = 5;
    }
    else // D3D8
    {
        if (flagsOrComp == 1) dxtMode = 1;
        else if (flagsOrComp == 3) dxtMode = 3;
        else if (flagsOrComp == 5) dxtMode = 5;
    }

    size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);

    if (dxtMode != 0)
    {
        if (!DecodeDxtImage(pixelData, mip0Size, width, height, dxtMode, rgba))
            return false;
    }
    else if (isPal8 && palette)
    {
        if (mip0Size < pixelCount) return false;
        rgba.resize(pixelCount * 4u);
        for (size_t i = 0; i < pixelCount; ++i)
        {
            const uint8_t* c = palette + static_cast<size_t>(pixelData[i]) * 4u;
            rgba[i * 4 + 0] = c[0];
            rgba[i * 4 + 1] = c[1];
            rgba[i * 4 + 2] = c[2];
            rgba[i * 4 + 3] = hasAlpha ? c[3] : 255u;
        }
    }
    else if (isPal4 && palette)
    {
        size_t need = (pixelCount + 1u) / 2u;
        if (mip0Size < need) return false;
        rgba.resize(pixelCount * 4u);
        for (size_t i = 0; i < pixelCount; ++i)
        {
            uint8_t b = pixelData[i >> 1];
            uint8_t idx = (i & 1u) ? ((b >> 4) & 0x0Fu) : (b & 0x0Fu);
            const uint8_t* c = palette + static_cast<size_t>(idx) * 4u;
            rgba[i * 4 + 0] = c[0];
            rgba[i * 4 + 1] = c[1];
            rgba[i * 4 + 2] = c[2];
            rgba[i * 4 + 3] = hasAlpha ? c[3] : 255u;
        }
    }
    else
    {
        uint32_t pixFmt = rasterFormat & rwRASTERFORMATPIXELFORMATMASK;
        rgba.resize(pixelCount * 4u);

        if (depth == 32 || pixFmt == rwRASTERFORMAT8888 || (pixFmt == rwRASTERFORMAT888 && depth != 24))
        {
            if (mip0Size < pixelCount * 4u) return false;
            bool opaque = (pixFmt == rwRASTERFORMAT888) || !hasAlpha;
            for (size_t i = 0; i < pixelCount; ++i)
            {
                // D3D8/D3D9 32-bit rasters store BGRA
                rgba[i * 4 + 0] = pixelData[i * 4 + 2];
                rgba[i * 4 + 1] = pixelData[i * 4 + 1];
                rgba[i * 4 + 2] = pixelData[i * 4 + 0];
                rgba[i * 4 + 3] = opaque ? 255u : pixelData[i * 4 + 3];
            }
        }
        else if (depth == 24 || pixFmt == rwRASTERFORMAT888)
        {
            if (mip0Size < pixelCount * 3u) return false;
            for (size_t i = 0; i < pixelCount; ++i)
            {
                rgba[i * 4 + 0] = pixelData[i * 3 + 2];
                rgba[i * 4 + 1] = pixelData[i * 3 + 1];
                rgba[i * 4 + 2] = pixelData[i * 3 + 0];
                rgba[i * 4 + 3] = 255u;
            }
        }
        else if (depth == 16)
        {
            if (mip0Size < pixelCount * 2u) return false;
            for (size_t i = 0; i < pixelCount; ++i)
            {
                uint16_t v = ReadU16LE(pixelData + i * 2u);
                if (pixFmt == rwRASTERFORMAT4444)
                {
                    rgba[i * 4 + 0] = static_cast<uint8_t>(((v >> 8) & 0x0Fu) * 17u);
                    rgba[i * 4 + 1] = static_cast<uint8_t>(((v >> 4) & 0x0Fu) * 17u);
                    rgba[i * 4 + 2] = static_cast<uint8_t>((v & 0x0Fu) * 17u);
                    rgba[i * 4 + 3] = static_cast<uint8_t>(((v >> 12) & 0x0Fu) * 17u);
                }
                else if (pixFmt == rwRASTERFORMAT565)
                {
                    UnpackRgb565(v, rgba[i * 4 + 0], rgba[i * 4 + 1], rgba[i * 4 + 2]);
                    rgba[i * 4 + 3] = 255u;
                }
                else // 1555 or 555
                {
                    uint8_t r5 = static_cast<uint8_t>((v >> 10) & 0x1Fu);
                    uint8_t g5 = static_cast<uint8_t>((v >> 5) & 0x1Fu);
                    uint8_t b5 = static_cast<uint8_t>(v & 0x1Fu);
                    rgba[i * 4 + 0] = static_cast<uint8_t>((r5 << 3) | (r5 >> 2));
                    rgba[i * 4 + 1] = static_cast<uint8_t>((g5 << 3) | (g5 >> 2));
                    rgba[i * 4 + 2] = static_cast<uint8_t>((b5 << 3) | (b5 >> 2));
                    rgba[i * 4 + 3] = (pixFmt == rwRASTERFORMAT1555 && hasAlpha) ? ((v & 0x8000u) ? 255u : 0u) : 255u;
                }
            }
        }
        else if (pixFmt == rwRASTERFORMATLUM8 && depth == 8)
        {
            if (mip0Size < pixelCount) return false;
            for (size_t i = 0; i < pixelCount; ++i)
            {
                uint8_t l = pixelData[i];
                rgba[i * 4 + 0] = l;
                rgba[i * 4 + 1] = l;
                rgba[i * 4 + 2] = l;
                rgba[i * 4 + 3] = 255u;
            }
        }
        else
        {
            return false;
        }
    }

    outTex = CreateRwTextureFromRgba(nameBuf, maskBuf, width, height, rgba, filterFlags);
    if (!outTex) return false;

    outNameLower = ToLowerAscii(nameBuf);
    return true;
}

// Optional native mobile TXD fallback via libGTASA RenderWare symbols (never calls exit())
struct NativeTxdCollectCtx
{
    std::unordered_map<std::string, RwTexture*>* outMap;
};

RwTexture* NativeTxdCollectCB(RwTexture* tex, void* data)
{
    auto* ctx = static_cast<NativeTxdCollectCtx*>(data);
    if (tex && tex->name[0] && ctx && ctx->outMap)
    {
        std::string key = ToLowerAscii(tex->name);
        if (ctx->outMap->find(key) == ctx->outMap->end())
        {
            RwTextureAddRef(tex);
            (*ctx->outMap)[key] = tex;
        }
    }
    return tex;
}

bool TryLoadMobileNativeTxd(const std::vector<uint8_t>& txdData, std::unordered_map<std::string, RwTexture*>& outMap)
{
    if (!CHook::lib || txdData.empty()) return false;

    using FnStreamOpen  = RwStream* (*)(RwStreamType, RwStreamAccessType, const void*);
    using FnStreamClose = RwBool (*)(RwStream*, void*);
    using FnDictRead    = void* (*)(RwStream*);
    using FnDictForAll  = void* (*)(void*, RwTextureCallBack, void*);
    using FnDictDestroy = RwBool (*)(void*);

    auto fnOpen    = reinterpret_cast<FnStreamOpen>(dlsym(CHook::lib, "_Z12RwStreamOpen12RwStreamType18RwStreamAccessTypePKv"));
    auto fnClose   = reinterpret_cast<FnStreamClose>(dlsym(CHook::lib, "_Z13RwStreamCloseP8RwStreamPv"));
    auto fnRead    = reinterpret_cast<FnDictRead>(dlsym(CHook::lib, "_Z28RwTexDictionaryGtaStreamReadP8RwStream"));
    if (!fnRead)
        fnRead     = reinterpret_cast<FnDictRead>(dlsym(CHook::lib, "_Z25RwTexDictionaryStreamReadP8RwStream"));
    auto fnForAll  = reinterpret_cast<FnDictForAll>(dlsym(CHook::lib, "_Z29RwTexDictionaryForAllTexturesPK15RwTexDictionaryPFP9RwTextureS3_PvES4_"));
    auto fnDestroy = reinterpret_cast<FnDictDestroy>(dlsym(CHook::lib, "_Z22RwTexDictionaryDestroyP15RwTexDictionary"));

    if (!fnOpen || !fnClose || !fnRead || !fnForAll || !fnDestroy)
        return false;

    RwMemory mem{};
    mem.start  = const_cast<RwUInt8*>(txdData.data());
    mem.length = static_cast<RwUInt32>(txdData.size());

    RwStream* stream = fnOpen(rwSTREAMMEMORY, rwSTREAMREAD, &mem);
    if (!stream) return false;

    void* dict = fnRead(stream);
    fnClose(stream, nullptr);
    if (!dict) return false;

    NativeTxdCollectCtx ctx{ &outMap };
    fnForAll(dict, &NativeTxdCollectCB, &ctx);
    fnDestroy(dict);
    return !outMap.empty();
}

DecodedTxdCache& GetOrLoadTxdCache(uint32_t txdCrc, const std::string& txdPath)
{
    auto& cache = s_txdCache[txdCrc];
    if (cache.triedLoad) return cache;
    cache.triedLoad = true;

    std::vector<uint8_t> data;
    if (!ReadWholeFile(txdPath, data, kMaxModelFileSizeBytes) || data.size() < 24)
    {
        FLog("0.3.DL: failed reading TXD %08X from %s", txdCrc, txdPath.c_str());
        return cache;
    }

    uint32_t rootType = ReadU32LE(data.data() + 0);
    uint32_t rootSize = ReadU32LE(data.data() + 4);
    if (rootType != kRwIdTexDictionary || 12u + rootSize > data.size())
    {
        FLog("0.3.DL: invalid RW TXD header for %08X (type=0x%X)", txdCrc, rootType);
        return cache;
    }

    bool sawNonPcPlatform = false;
    size_t pos = 12;
    size_t rootEnd = 12u + rootSize;

    while (pos + 12 <= rootEnd)
    {
        uint32_t cType = ReadU32LE(data.data() + pos + 0);
        uint32_t cSize = ReadU32LE(data.data() + pos + 4);
        size_t   cBody = pos + 12;
        if (cBody + cSize > rootEnd) break;

        if (cType == kRwIdTextureNative && cSize >= 12)
        {
            uint32_t subType = ReadU32LE(data.data() + cBody + 0);
            uint32_t subSize = ReadU32LE(data.data() + cBody + 4);
            size_t   subBody = cBody + 12;
            if (subType == kRwIdStruct && subBody + subSize <= cBody + cSize && subSize >= 4)
            {
                uint32_t plat = ReadU32LE(data.data() + subBody);
                if (plat == kRwPlatformD3D8 || plat == kRwPlatformD3D9)
                {
                    RwTexture* tex = nullptr;
                    std::string lowerName;
                    if (DecodePcTextureNative(data.data() + subBody, subSize, tex, lowerName) && tex)
                    {
                        auto it = cache.textures.find(lowerName);
                        if (it != cache.textures.end())
                        {
                            RwTextureDestroy(it->second);
                            it->second = tex;
                        }
                        else
                        {
                            cache.textures.emplace(std::move(lowerName), tex);
                        }
                    }
                }
                else
                {
                    sawNonPcPlatform = true;
                }
            }
        }

        pos = cBody + cSize;
    }

    if (cache.textures.empty() && sawNonPcPlatform)
    {
        TryLoadMobileNativeTxd(data, cache.textures);
    }

    FLog("0.3.DL: decoded TXD %08X -> %zu texture(s)", txdCrc, cache.textures.size());
    return cache;
}

// ============================================================================
// Temporary RwTextureFindCallBack Scope Guard during Custom DFF Load
// ============================================================================
RwTexture* CustomTextureFindCB(const RwChar* name)
{
    if (name && name[0] && s_activeTxdMap)
    {
        std::string key = ToLowerAscii(name);
        auto it = s_activeTxdMap->find(key);
        if (it != s_activeTxdMap->end() && it->second)
        {
            RwTextureAddRef(it->second);
            return it->second;
        }
    }
    if (s_prevFindCallback && s_prevFindCallback != &CustomTextureFindCB)
    {
        return s_prevFindCallback(name);
    }
    return nullptr;
}

RwTextureCallBackFind ResolveCurrentTextureFindCB()
{
    if (CHook::lib)
    {
        using FnGetFindCB = RwTextureCallBackFind (*)();
        auto fnGet = reinterpret_cast<FnGetFindCB>(dlsym(CHook::lib, "_Z24RwTextureGetFindCallBackv"));
        if (fnGet)
        {
            RwTextureCallBackFind cur = fnGet();
            if (cur) return cur;
        }
        auto fnDefault = reinterpret_cast<RwTextureCallBackFind>(dlsym(CHook::lib, "_ZN9CTxdStore14TxdStoreFindCBEPKc"));
        if (fnDefault) return fnDefault;
    }
    return nullptr;
}

struct ScopedCustomTextureFind
{
    bool installed = false;
    RwTextureCallBackFind savedPrev = nullptr;
    const std::unordered_map<std::string, RwTexture*>* savedMap = nullptr;

    explicit ScopedCustomTextureFind(const std::unordered_map<std::string, RwTexture*>* texMap)
    {
        if (!texMap || texMap->empty()) return;
        RwTextureCallBackFind prev = ResolveCurrentTextureFindCB();
        if (prev == &CustomTextureFindCB)
        {
            prev = s_prevFindCallback;
        }
        if (!prev)
        {
            FLog("0.3.DL: unable to resolve current RwTextureFindCallBack; skipping TXD hook");
            return;
        }
        savedPrev = s_prevFindCallback;
        savedMap  = s_activeTxdMap;
        s_prevFindCallback = prev;
        s_activeTxdMap     = texMap;
        RwTextureSetFindCallBack(&CustomTextureFindCB);
        installed = true;
    }

    ~ScopedCustomTextureFind()
    {
        if (installed)
        {
            RwTextureSetFindCallBack(s_prevFindCallback);
            s_prevFindCallback = savedPrev;
            s_activeTxdMap     = savedMap;
        }
    }
};

// ============================================================================
// DFF Frame NodeName Sanitizer (prevents 24-byte heap/stack overflow in libGTASA)
// ============================================================================
void WriteU32LE(uint8_t* p, uint32_t v)
{
    p[0] = static_cast<uint8_t>(v & 0xFFu);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFFu);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFFu);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xFFu);
}

bool IsRwContainerChunk(uint32_t id)
{
    switch (id)
    {
        case 0x0010u: // rwID_CLUMP
        case 0x000Eu: // rwID_FRAMELIST
        case 0x0003u: // rwID_EXTENSION
        case 0x0014u: // rwID_ATOMIC
        case 0x001Au: // rwID_GEOMETRYLIST
        case 0x000Fu: // rwID_GEOMETRY
        case 0x0008u: // rwID_MATLIST
        case 0x0007u: // rwID_MATERIAL
        case 0x0006u: // rwID_TEXTURE
            return true;
        default:
            return false;
    }
}

bool CanTileRwChunks(const uint8_t* data, size_t len)
{
    size_t pos = 0;
    while (pos < len)
    {
        if (len - pos < 12u) return false;
        uint32_t sz = ReadU32LE(data + pos + 4);
        if (sz > len - pos - 12u) return false;
        pos += 12u + sz;
    }
    return pos == len;
}

void SanitizeNodeNamePayload(const uint8_t* body, size_t bodySize, std::string& outName)
{
    size_t effLen = 0;
    while (effLen < bodySize && body[effLen] != 0)
    {
        ++effLen;
    }

    // GTA SA NodeNameStreamRead stores into a 24-byte RwFrame slot, while
    // CFileLoader::SetRelatedModelInfoCB & FindRelatedModelInfoCB pass char name[20]
    // (21 bytes before the ARM32 stack canary). Clamp strictly to <= 19 chars so both are safe.
    if (effLen <= 19u)
    {
        outName.assign(reinterpret_cast<const char*>(body), effLen);
        return;
    }

    if (effLen >= 4u &&
        body[effLen - 4] == '_' &&
        (body[effLen - 3] == 'd' || body[effLen - 3] == 'D') &&
        (body[effLen - 2] == 'a' || body[effLen - 2] == 'A') &&
        (body[effLen - 1] == 'm' || body[effLen - 1] == 'M'))
    {
        outName.assign(reinterpret_cast<const char*>(body), 15u);
        outName.append(reinterpret_cast<const char*>(body + effLen - 4), 4u);
        return;
    }

    if (effLen >= 3u &&
        body[effLen - 3] == '_' &&
        (body[effLen - 2] == 'l' || body[effLen - 2] == 'L') &&
        body[effLen - 1] == '0')
    {
        outName.assign(reinterpret_cast<const char*>(body), 16u);
        outName.append(reinterpret_cast<const char*>(body + effLen - 3), 3u);
        return;
    }

    outName.assign(reinterpret_cast<const char*>(body), 19u);
}

void SanitizeRwChunkSequence(const uint8_t* data, size_t len, std::vector<uint8_t>& out, int depth, int& outFixedCount)
{
    size_t pos = 0;
    while (pos + 12u <= len)
    {
        uint32_t id   = ReadU32LE(data + pos + 0);
        uint32_t size = ReadU32LE(data + pos + 4);
        uint32_t ver  = ReadU32LE(data + pos + 8);
        if (size > len - pos - 12u)
        {
            out.insert(out.end(), data + pos, data + len);
            return;
        }

        const uint8_t* body = data + pos + 12u;

        if (id == kRwIdNodeName && size > 19u)
        {
            std::string trimmed;
            SanitizeNodeNamePayload(body, size, trimmed);
            size_t hdrOff = out.size();
            out.resize(hdrOff + 12u + trimmed.size());
            WriteU32LE(out.data() + hdrOff + 0, id);
            WriteU32LE(out.data() + hdrOff + 4, static_cast<uint32_t>(trimmed.size()));
            WriteU32LE(out.data() + hdrOff + 8, ver);
            if (!trimmed.empty())
            {
                memcpy(out.data() + hdrOff + 12u, trimmed.data(), trimmed.size());
            }
            ++outFixedCount;
        }
        else if (depth < 16 && IsRwContainerChunk(id) && size > 0u && CanTileRwChunks(body, size))
        {
            size_t hdrOff = out.size();
            out.resize(hdrOff + 12u);
            WriteU32LE(out.data() + hdrOff + 0, id);
            WriteU32LE(out.data() + hdrOff + 4, 0u);
            WriteU32LE(out.data() + hdrOff + 8, ver);
            size_t bodyStart = out.size();
            SanitizeRwChunkSequence(body, size, out, depth + 1, outFixedCount);
            uint32_t newSize = static_cast<uint32_t>(out.size() - bodyStart);
            WriteU32LE(out.data() + hdrOff + 4, newSize);
        }
        else
        {
            out.insert(out.end(), data + pos, data + pos + 12u + size);
        }

        pos += 12u + size;
    }

    if (pos < len)
    {
        out.insert(out.end(), data + pos, data + len);
    }
}

void SanitizeDffNodeNamesInPlace(std::vector<uint8_t>& dffBytes, uint32_t dffCrc)
{
    if (dffBytes.size() < 12u) return;
    std::vector<uint8_t> sanitized;
    sanitized.reserve(dffBytes.size());
    int fixedCount = 0;
    SanitizeRwChunkSequence(dffBytes.data(), dffBytes.size(), sanitized, 0, fixedCount);
    if (fixedCount > 0 && sanitized.size() >= 12u)
    {
        FLog("0.3.DL: sanitized %d oversized NodeName chunk(s) in DFF %08X", fixedCount, dffCrc);
        dffBytes.swap(sanitized);
    }
}

// ============================================================================
// Internal GTA SA Slot Allocation & Engine Model Loader
// ============================================================================
int32_t AllocateInternalSlot(uint8_t modelType, bool& outReusedExistingInfo)
{
    outReusedExistingInfo = false;

    auto& reusePool = (modelType == CCustomModels::MODEL_TYPE_CHAR) ? s_reusablePedSlots : s_reusableObjSlots;
    while (!reusePool.empty())
    {
        int32_t slot = reusePool.back();
        reusePool.pop_back();
        if (slot >= kMinDynamicSlot && slot <= kMaxDynamicSlot &&
            s_serverIdBySlot.find(slot) == s_serverIdBySlot.end() &&
            CModelInfo::GetModelInfo(slot) != nullptr)
        {
            outReusedExistingInfo = true;
            return slot;
        }
    }

    while (s_nextScanSlot >= kMinDynamicSlot)
    {
        int32_t candidate = s_nextScanSlot--;
        if (candidate == kFallbackObjectModel) continue;
        if (s_customSlots.find(candidate) != s_customSlots.end()) continue;
        if (CModelInfo::ms_modelInfoPtrs[candidate] == nullptr)
        {
            return candidate;
        }
    }

    FLog("0.3.DL: no free internal GTA model slots left!");
    return -1;
}

bool LoadCustomModelIntoEngine(CustomModelEntry& entry)
{
    if (entry.loadState == ModelLoadState::Loaded)
        return true;

    // Avoid interfering with an active GTA SA big-model streaming read
    if (CStreaming::ms_bLoadingBigModel)
        return false;

    auto dffIt = s_files.find(FileKey{ CCustomModels::FILE_TYPE_DFF, entry.dffCrc });
    if (dffIt == s_files.end() || dffIt->second.state != FileState::Ready)
    {
        entry.loadState = ModelLoadState::Failed;
        return false;
    }

    std::vector<uint8_t> dffBytes;
    if (!ReadWholeFile(dffIt->second.cachePath, dffBytes, kMaxModelFileSizeBytes) || dffBytes.size() < 12)
    {
        FLog("0.3.DL: DFF unreadable for model %d (crc %08X)", entry.newId, entry.dffCrc);
        entry.loadState = ModelLoadState::Failed;
        return false;
    }

    uint32_t dffRootId = ReadU32LE(dffBytes.data() + 0);
    if (dffRootId != 0x0010u && dffRootId != 0x002Bu)
    {
        FLog("0.3.DL: invalid DFF root chunk 0x%X for serverId=%d (crc %08X)", dffRootId, entry.newId, entry.dffCrc);
        entry.loadState = ModelLoadState::Failed;
        return false;
    }

    FLog("0.3.DL: loading model serverId=%d (type=%u, base=%d, dff=%08X[%zu], txd=%08X)",
         entry.newId, entry.type, entry.baseId, entry.dffCrc, dffBytes.size(), entry.txdCrc);

    // Clamp oversized RW Frame NodeName (0x0253F2FE) chunks to <= 19 bytes before ConvertBufferToObject
    // to prevent heap overflow in NodeNameStreamRead and stack canary SIGABRT in SetRelatedModelInfoCB (char name[20]).
    SanitizeDffNodeNamesInPlace(dffBytes, entry.dffCrc);

    // Pad DFF to STREAMING_SECTOR_SIZE (2048 bytes) for CStreaming::ConvertBufferToObject
    size_t paddedSize = ((dffBytes.size() + STREAMING_SECTOR_SIZE - 1u) / STREAMING_SECTOR_SIZE) * STREAMING_SECTOR_SIZE;
    if (paddedSize == 0) paddedSize = STREAMING_SECTOR_SIZE;
    dffBytes.resize(paddedSize, 0);

    // Load TXD textures (optional if txdCrc == 0 or TXD failed — model can still fall back to vanilla textures)
    const std::unordered_map<std::string, RwTexture*>* texMap = nullptr;
    if (entry.txdCrc != 0)
    {
        auto txdIt = s_files.find(FileKey{ CCustomModels::FILE_TYPE_TXD, entry.txdCrc });
        if (txdIt != s_files.end() && txdIt->second.state == FileState::Ready)
        {
            DecodedTxdCache& txd = GetOrLoadTxdCache(entry.txdCrc, txdIt->second.cachePath);
            if (!txd.textures.empty())
                texMap = &txd.textures;
        }
    }

    // Allocate or reuse internal GTA SA model slot (< 20000)
    bool reusedInfo = false;
    int32_t slot = entry.internalSlot;
    if (slot < 0)
    {
        slot = AllocateInternalSlot(entry.type, reusedInfo);
        if (slot < 0)
        {
            entry.loadState = ModelLoadState::Failed;
            return false;
        }
    }
    else
    {
        reusedInfo = (CModelInfo::GetModelInfo(slot) != nullptr);
    }

    CBaseModelInfo* mi = nullptr;
    CBaseModelInfo* baseMi = nullptr;

    if (entry.type == CCustomModels::MODEL_TYPE_CHAR)
    {
        int32_t safeBase = (entry.baseId >= 0 && entry.baseId < TOTAL_DFF_MODEL_IDS &&
                            CModelInfo::GetModelInfo(entry.baseId) &&
                            CModelInfo::GetModelInfo(entry.baseId)->GetModelType() == MODEL_INFO_PED)
            ? entry.baseId : kFallbackPedModel;

        baseMi = CModelInfo::GetModelInfo(safeBase);
        if (!baseMi)
        {
            FLog("0.3.DL: missing base ped model %d for custom skin %d", safeBase, entry.newId);
            entry.loadState = ModelLoadState::Failed;
            return false;
        }

        if (!reusedInfo && CModelInfo::ms_pedModelInfoStore.m_nCount >= CModelInfo::NUM_PED_MODEL_INFOS)
        {
            FLog("0.3.DL: ped ModelInfo store full (%zu), skipping serverId=%d",
                 CModelInfo::ms_pedModelInfoStore.m_nCount, entry.newId);
            entry.loadState = ModelLoadState::Failed;
            return false;
        }

        CPedModelInfo* pedMi = reusedInfo
            ? CModelInfo::GetModelInfo(slot)->AsPedModelInfoPtr()
            : CModelInfo::AddPedModel(slot);

        if (!pedMi)
        {
            entry.loadState = ModelLoadState::Failed;
            return false;
        }

        if (pedMi->m_pRwObject)
        {
            pedMi->DeleteRwObject();
        }

        CPedModelInfo* basePed = baseMi->AsPedModelInfoPtr();
        pedMi->m_nTxdIndex         = (basePed->m_nTxdIndex >= 0) ? basePed->m_nTxdIndex : 0;
        pedMi->m_nAlpha            = 255;
        pedMi->m_nFlags            = basePed->m_nFlags & static_cast<uint16_t>(~(1u << 5));
        pedMi->m_pColModel         = basePed->m_pColModel;
        pedMi->m_fDrawDistance     = (basePed->m_fDrawDistance > 1.0f) ? basePed->m_fDrawDistance : 300.0f;
        pedMi->m_dwAnimFileIndex   = basePed->m_dwAnimFileIndex;
        pedMi->m_nAnimType         = basePed->m_nAnimType;
        pedMi->m_nPedType          = basePed->m_nPedType;
        pedMi->m_nStatType         = basePed->m_nStatType;
        pedMi->m_nCarsCanDriveMask = basePed->m_nCarsCanDriveMask;
        pedMi->m_nPedFlags         = basePed->m_nPedFlags;
        pedMi->m_pHitColModel      = basePed->m_pHitColModel;
        pedMi->m_nRadio1           = basePed->m_nRadio1;
        pedMi->m_nRadio2           = basePed->m_nRadio2;
        pedMi->m_nRace             = basePed->m_nRace;
        pedMi->m_AudioPedType      = basePed->m_AudioPedType;
        pedMi->m_FirstVoice        = basePed->m_FirstVoice;
        pedMi->m_LastVoice         = basePed->m_LastVoice;
        pedMi->m_NextVoice         = basePed->m_NextVoice;

        char mname[21];
        snprintf(mname, sizeof(mname), "dlped_%d", entry.newId);
        strncpy(pedMi->m_modelName, mname, sizeof(pedMi->m_modelName) - 1);
        pedMi->m_modelName[sizeof(pedMi->m_modelName) - 1] = '\0';
        pedMi->SetModelName(mname);

        const int32 animFileIdx = pedMi->GetAnimFileIndex();
        if (animFileIdx != -1)
        {
            CStreaming::RequestModel(IFPToModelId(animFileIdx), STREAMING_KEEP_IN_MEMORY);
        }

        mi = pedMi;
    }
    else
    {
        int32_t safeBase = (entry.baseId >= 0 && entry.baseId < TOTAL_DFF_MODEL_IDS &&
                            CModelInfo::GetModelInfo(entry.baseId))
            ? entry.baseId : kFallbackObjectModel;
        baseMi = CModelInfo::GetModelInfo(safeBase);
        if (!baseMi) baseMi = CModelInfo::GetModelInfo(kFallbackObjectModel);

        if (!reusedInfo && CModelInfo::ms_atomicModelInfoStore.m_nCount >= 20000u)
        {
            FLog("0.3.DL: atomic ModelInfo store full (%zu), skipping serverId=%d",
                 CModelInfo::ms_atomicModelInfoStore.m_nCount, entry.newId);
            entry.loadState = ModelLoadState::Failed;
            return false;
        }

        CAtomicModelInfo* objMi = reusedInfo
            ? reinterpret_cast<CAtomicModelInfo*>(CModelInfo::GetModelInfo(slot))
            : CModelInfo::AddAtomicModel(slot);

        if (!objMi)
        {
            entry.loadState = ModelLoadState::Failed;
            return false;
        }

        if (objMi->m_pRwObject)
        {
            objMi->DeleteRwObject();
        }

        objMi->m_nTxdIndex     = (baseMi && baseMi->m_nTxdIndex >= 0) ? baseMi->m_nTxdIndex : 0;
        objMi->m_nAlpha        = 255;
        objMi->m_nFlags        = baseMi ? (baseMi->m_nFlags & static_cast<uint16_t>(~(1u << 5))) : 0;
        objMi->m_pColModel     = nullptr; // Let embedded DFF COL populate if present
        objMi->m_fDrawDistance = (baseMi && baseMi->m_fDrawDistance > 10.0f) ? baseMi->m_fDrawDistance : 300.0f;

        char mname[21];
        snprintf(mname, sizeof(mname), "dlobj_%d", entry.newId);
        strncpy(objMi->m_modelName, mname, sizeof(objMi->m_modelName) - 1);
        objMi->m_modelName[sizeof(objMi->m_modelName) - 1] = '\0';
        objMi->SetModelName(mname);

        mi = objMi;
    }

    // Prepare CStreamingInfo for ConvertBufferToObject
    CStreamingInfo& info = CStreaming::GetInfo(slot);
    if (info.InList())
    {
        info.RemoveFromList();
    }
    info.m_nNextIndex     = -1;
    info.m_nPrevIndex     = -1;
    info.m_nNextIndexOnCd = -1;
    info.m_nImgId         = 0;
    info.m_nCdPosn        = 0;
    info.m_nCdSize        = static_cast<uint32>(paddedSize / STREAMING_SECTOR_SIZE);
    info.m_nFlags         = STREAMING_KEEP_IN_MEMORY | STREAMING_GAME_REQUIRED | STREAMING_MISSION_REQUIRED;
    info.m_nLoadState     = LOADSTATE_NOT_LOADED;

    bool converted = false;
    {
        ScopedCustomTextureFind texGuard(texMap);
        converted = CStreaming::ConvertBufferToObject(dffBytes.data(), slot);
    }

    if (info.InList())
    {
        info.RemoveFromList();
    }

    if (!converted || !mi->m_pRwObject)
    {
        FLog("0.3.DL: ConvertBufferToObject failed for serverId=%d (slot=%d, dff=%08X)",
             entry.newId, slot, entry.dffCrc);
        if (mi->m_pRwObject)
        {
            mi->DeleteRwObject();
        }
        info.m_nLoadState = LOADSTATE_NOT_LOADED;
        s_customSlots.insert(slot);
        if (entry.type == CCustomModels::MODEL_TYPE_CHAR)
            s_reusablePedSlots.push_back(slot);
        else
            s_reusableObjSlots.push_back(slot);
        entry.internalSlot = -1;
        entry.loadState = ModelLoadState::Failed;
        return false;
    }

    // For custom objects without an embedded DFF COL chunk, borrow base model's collision (non-owning)
    if (entry.type == CCustomModels::MODEL_TYPE_SIMPLE && mi->m_pColModel == nullptr)
    {
        CBaseModelInfo* colSrc = (baseMi && baseMi->m_pColModel)
            ? baseMi
            : CModelInfo::GetModelInfo(kFallbackObjectModel);
        if (colSrc && colSrc->m_pColModel)
        {
            mi->m_pColModel = colSrc->m_pColModel;
            mi->bDoWeOwnTheColModel = 0;
        }
    }

    info.m_nLoadState = LOADSTATE_LOADED;
    info.SetFlags(STREAMING_KEEP_IN_MEMORY | STREAMING_GAME_REQUIRED | STREAMING_MISSION_REQUIRED);

    entry.internalSlot = slot;
    entry.loadState = ModelLoadState::Loaded;
    s_customSlots.insert(slot);
    s_serverIdBySlot[slot] = entry.newId;

    FLog("0.3.DL: loaded model serverId=%d -> slot=%d (type=%u, base=%d, dff=%08X, txd=%08X)",
         entry.newId, slot, entry.type, entry.baseId, entry.dffCrc, entry.txdCrc);
    return true;
}

// ============================================================================
// Download Queue Helpers
// ============================================================================
void EnqueueFileIfNeeded(uint8_t fileType, uint32_t crc, uint32_t expectedSize)
{
    if (crc == 0) return;

    FileKey key{ fileType, crc };
    auto it = s_files.find(key);
    if (it != s_files.end())
    {
        if (it->second.expectedSize == 0 && expectedSize > 0)
            it->second.expectedSize = expectedSize;
        return;
    }

    FileTask task{};
    task.type         = fileType;
    task.crc          = crc;
    task.expectedSize = expectedSize;
    task.cachePath    = BuildCachePath(fileType, crc);

    uint32_t diskCrc = 0;
    uint32_t diskSize = 0;
    if (CCustomModels::CalcFileCrc32(task.cachePath, diskCrc, diskSize) &&
        diskCrc == crc &&
        (expectedSize == 0 || diskSize == expectedSize))
    {
        task.state = FileState::Ready;
        s_files.emplace(key, std::move(task));
        return;
    }

    task.state = FileState::Pending;
    s_files.emplace(key, std::move(task));
    s_downloadQueue.push_back(key);
}

void UpdateWaitingModelsForFile(uint8_t fileType, uint32_t crc)
{
    for (auto& kv : s_modelsByServerId)
    {
        CustomModelEntry& m = kv.second;
        if (m.loadState != ModelLoadState::WaitingFiles) continue;

        bool dffDone = false;
        bool dffOk = false;
        {
            auto it = s_files.find(FileKey{ CCustomModels::FILE_TYPE_DFF, m.dffCrc });
            if (it != s_files.end())
            {
                if (it->second.state == FileState::Ready) { dffDone = true; dffOk = true; }
                else if (it->second.state == FileState::Failed) { dffDone = true; dffOk = false; }
            }
            else if (m.dffCrc == 0)
            {
                dffDone = true;
                dffOk = false;
            }
        }

        bool txdDone = (m.txdCrc == 0);
        if (!txdDone)
        {
            auto it = s_files.find(FileKey{ CCustomModels::FILE_TYPE_TXD, m.txdCrc });
            if (it != s_files.end() &&
                (it->second.state == FileState::Ready || it->second.state == FileState::Failed))
            {
                txdDone = true;
            }
        }

        if (dffDone && txdDone)
        {
            m.loadState = dffOk ? ModelLoadState::ReadyToLoad : ModelLoadState::Failed;
        }
    }
    (void)fileType;
    (void)crc;
}

size_t CountActiveDownloads()
{
    size_t active = 0;
    for (const auto& kv : s_files)
    {
        if (kv.second.state == FileState::RequestedRakNet ||
            kv.second.state == FileState::DownloadingHttp)
        {
            ++active;
        }
    }
    return active;
}

size_t CountIncompleteFiles()
{
    size_t pending = 0;
    for (const auto& kv : s_files)
    {
        if (kv.second.state != FileState::Ready && kv.second.state != FileState::Failed)
            ++pending;
    }
    return pending;
}

size_t CountModelsPendingLoad()
{
    size_t count = 0;
    for (const auto& kv : s_modelsByServerId)
    {
        if (kv.second.loadState == ModelLoadState::ReadyToLoad ||
            kv.second.loadState == ModelLoadState::WaitingFiles)
            ++count;
    }
    return count;
}

void SendFileRequestRpc(RakClientInterface* pRakClient, FileTask& task)
{
    if (!pRakClient) return;

    RakNet::BitStream bs;
    bs.Write(task.crc);

    int& rpcId = (task.type == CCustomModels::FILE_TYPE_TXD) ? RPC_RequestTxdFile : RPC_RequestDffFile;
    pRakClient->RPC(&rpcId, &bs, MEDIUM_PRIORITY, RELIABLE_ORDERED, 0, false, UNASSIGNED_NETWORK_ID, nullptr);

    task.state = FileState::RequestedRakNet;
    task.requestSentTick = GetTickCount();
    task.rakNetBuffer.clear();

    FLog("0.3.DL: requested %s CRC=%08X (size=%u)",
         (task.type == CCustomModels::FILE_TYPE_TXD) ? "TXD" : "DFF",
         task.crc, task.expectedSize);
}

void SendFinishedDownloadingRpc(RakClientInterface* pRakClient)
{
    if (!pRakClient || s_finishedSent) return;

    RakNet::BitStream bs;
    bs.Write(static_cast<uint32_t>(0x80)); // Little-endian 0x80 satisfies both UINT8 and UINT32 readers
    pRakClient->RPC(&RPC_FinishedDownloading, &bs, HIGH_PRIORITY, RELIABLE_ORDERED, 0, false, UNASSIGNED_NETWORK_ID, nullptr);

    s_finishedSent = true;
    s_batchActive = false;

    size_t loadedCount = 0;
    size_t failedCount = 0;
    for (const auto& kv : s_modelsByServerId)
    {
        if (kv.second.loadState == ModelLoadState::Loaded) ++loadedCount;
        else if (kv.second.loadState == ModelLoadState::Failed) ++failedCount;
    }

    FLog("0.3.DL: sent FinishedDownloading (loaded=%zu, failed=%zu)", loadedCount, failedCount);
    if (pUI && pUI->chat() && (loadedCount > 0 || failedCount > 0))
    {
        pUI->chat()->addDebugMessage("{88CCFF}[0.3.DL] Custom models ready: %zu loaded%s",
                                     loadedCount,
                                     failedCount > 0 ? " (some failed, using fallback)" : "");
    }
}

void FinalizeFileTask(FileTask& task, bool ok)
{
    task.rakNetBuffer.clear();
    task.rakNetBuffer.shrink_to_fit();

    if (ok)
    {
        task.state = FileState::Ready;
        FLog("0.3.DL: cached %s CRC=%08X",
             (task.type == CCustomModels::FILE_TYPE_TXD) ? "TXD" : "DFF",
             task.crc);
        UpdateWaitingModelsForFile(task.type, task.crc);
        return;
    }

    if (task.retries < kMaxRetriesPerFile)
    {
        task.retries++;
        task.state = FileState::Pending;
        s_downloadQueue.push_back(FileKey{ task.type, task.crc });
        FLog("0.3.DL: retrying %s CRC=%08X (%d/%d)",
             (task.type == CCustomModels::FILE_TYPE_TXD) ? "TXD" : "DFF",
             task.crc, task.retries, kMaxRetriesPerFile);
        return;
    }

    task.state = FileState::Failed;
    FLog("0.3.DL: failed %s CRC=%08X permanently",
         (task.type == CCustomModels::FILE_TYPE_TXD) ? "TXD" : "DFF",
         task.crc);
    UpdateWaitingModelsForFile(task.type, task.crc);
}

void ProcessCompletedHttpDownloads()
{
    std::vector<HttpResult> completed;
    {
        std::lock_guard<std::mutex> lock(s_httpMutex);
        if (s_httpResults.empty()) return;
        completed.swap(s_httpResults);
    }

    for (const auto& res : completed)
    {
        auto it = s_files.find(FileKey{ res.fileType, res.crc });
        if (it == s_files.end()) continue;

        FileTask& task = it->second;
        if (task.state != FileState::DownloadingHttp) continue;

        bool valid = false;
        if (res.success)
        {
            uint32_t actualCrc = 0;
            uint32_t actualSize = 0;
            if (CCustomModels::CalcFileCrc32(task.cachePath, actualCrc, actualSize) &&
                actualCrc == task.crc &&
                (task.expectedSize == 0 || actualSize == task.expectedSize))
            {
                valid = true;
            }
            else
            {
                FLog("0.3.DL: HTTP CRC/size mismatch for %08X (got crc=%08X size=%u, exp size=%u)",
                     task.crc, actualCrc, actualSize, task.expectedSize);
                remove(task.cachePath.c_str());
            }
        }
        FinalizeFileTask(task, valid);
    }
}

void CheckDownloadTimeouts()
{
    uint32_t now = GetTickCount();
    for (auto& kv : s_files)
    {
        FileTask& task = kv.second;
        if (task.state == FileState::RequestedRakNet || task.state == FileState::DownloadingHttp)
        {
            if (now - task.requestSentTick > kDownloadTimeoutMs)
            {
                FLog("0.3.DL: timeout downloading %s CRC=%08X",
                     (task.type == CCustomModels::FILE_TYPE_TXD) ? "TXD" : "DFF",
                     task.crc);
                FinalizeFileTask(task, false);
            }
        }
    }
}

} // anonymous namespace

// ============================================================================
// Public CCustomModels API
// ============================================================================
uint32_t CCustomModels::CalcCrc32(const uint8_t* data, size_t len)
{
    const uint32_t* table = GetCrc32Table();
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i)
    {
        crc = table[(crc ^ data[i]) & 0xFFu] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFFu;
}

bool CCustomModels::CalcFileCrc32(const std::string& path, uint32_t& outCrc, uint32_t& outSize)
{
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return false;

    const uint32_t* table = GetCrc32Table();
    uint32_t crc = 0xFFFFFFFFu;
    uint32_t total = 0;
    uint8_t buf[16384];

    size_t n = 0;
    while ((n = fread(buf, 1, sizeof(buf), fp)) > 0)
    {
        for (size_t i = 0; i < n; ++i)
        {
            crc = table[(crc ^ buf[i]) & 0xFFu] ^ (crc >> 8);
        }
        total += static_cast<uint32_t>(n);
        if (total > kMaxModelFileSizeBytes)
        {
            fclose(fp);
            return false;
        }
    }

    bool hadErr = ferror(fp) != 0;
    fclose(fp);
    if (hadErr || total == 0) return false;

    outCrc  = crc ^ 0xFFFFFFFFu;
    outSize = total;
    return true;
}

void CCustomModels::Init(const char* szHostOrIp, int iPort)
{
    Reset();

    std::string baseStorage = (g_pszStorage && g_pszStorage[0]) ? std::string(g_pszStorage) : std::string();
    if (!baseStorage.empty() && baseStorage.back() != '/')
        baseStorage.push_back('/');

    s_cacheDir = baseStorage + "SAMP/cache/" + SanitizeHostDir(szHostOrIp, iPort);
    EnsureDirectoryTree(s_cacheDir);
    s_initialized = true;

    FLog("0.3.DL: CustomModels initialized (cache=%s)", s_cacheDir.c_str());
}

void CCustomModels::Reset()
{
    // Release loaded RW objects safely and recycle slots for future reconnects/GMX
    for (auto& kv : s_modelsByServerId)
    {
        CustomModelEntry& entry = kv.second;
        if (entry.internalSlot >= kMinDynamicSlot && entry.internalSlot <= kMaxDynamicSlot)
        {
            CBaseModelInfo* mi = CModelInfo::GetModelInfo(entry.internalSlot);
            if (mi && mi->m_nRefCount == 0 && mi->m_pRwObject)
            {
                mi->DeleteRwObject();
                CStreamingInfo& info = CStreaming::GetInfo(entry.internalSlot);
                info.m_nLoadState = LOADSTATE_NOT_LOADED;
            }
            if (mi)
            {
                auto& pool = (entry.type == MODEL_TYPE_CHAR) ? s_reusablePedSlots : s_reusableObjSlots;
                if (std::find(pool.begin(), pool.end(), entry.internalSlot) == pool.end())
                    pool.push_back(entry.internalSlot);
            }
        }
    }

    // Release cached RwTextures owned by our TXD map
    for (auto& kv : s_txdCache)
    {
        for (auto& texKv : kv.second.textures)
        {
            if (texKv.second)
                RwTextureDestroy(texKv.second);
        }
    }
    s_txdCache.clear();

    s_modelsByServerId.clear();
    s_serverIdBySlot.clear();
    s_files.clear();
    s_downloadQueue.clear();

    {
        std::lock_guard<std::mutex> lock(s_httpMutex);
        s_httpResults.clear();
    }

    s_currentVirtualWorld = 0;
    s_expectedBatchCount  = 0;
    s_receivedBatchCount  = 0;
    s_lastModelInfoTick   = 0;
    s_batchActive         = false;
    s_finishedSent        = false;
    s_lastProgressChatTick = 0;
}

void CCustomModels::OnModelInfoRPC(RPCParameters* rpcParams)
{
    if (!rpcParams || !rpcParams->input || rpcParams->numberOfBitsOfData < 8)
        return;

    uint8_t* data = reinterpret_cast<uint8_t*>(rpcParams->input);
    int byteLen = (rpcParams->numberOfBitsOfData + 7) / 8;
    RakNet::BitStream bs(data, byteLen, false);

    uint32_t index = 0;
    int32_t  count = 0;
    uint8_t  type = 0;
    int32_t  virtualWorld = -1;
    int32_t  baseId = 0;
    int32_t  newId = 0;
    uint32_t dffCrc = 0;
    uint32_t txdCrc = 0;
    uint32_t dffSize = 0;
    uint32_t txdSize = 0;
    uint8_t  timeOn = 0;
    uint8_t  timeOff = 0;

    if (!bs.Read(index)) return;
    if (!bs.Read(count)) return;
    if (!bs.Read(type)) return;
    if (!bs.Read(virtualWorld)) return;
    if (!bs.Read(baseId)) return;
    if (!bs.Read(newId)) return;
    if (!bs.Read(dffCrc)) return;
    if (!bs.Read(txdCrc)) return;
    if (!bs.Read(dffSize)) return;
    if (!bs.Read(txdSize)) return;
    bs.Read(timeOn);
    bs.Read(timeOff);

    if (dffSize > kMaxModelFileSizeBytes || txdSize > kMaxModelFileSizeBytes)
    {
        FLog("0.3.DL: ignoring oversized ModelInfo newId=%d dffSize=%u txdSize=%u", newId, dffSize, txdSize);
        return;
    }

    if (type != MODEL_TYPE_CHAR && type != MODEL_TYPE_SIMPLE)
    {
        FLog("0.3.DL: unknown ModelInfo type=%u newId=%d", type, newId);
        return;
    }

    if (!s_batchActive || s_finishedSent)
    {
        s_batchActive = true;
        s_finishedSent = false;
        s_receivedBatchCount = 0;
        s_expectedBatchCount = count;
    }
    else if (count > s_expectedBatchCount)
    {
        s_expectedBatchCount = count;
    }

    s_receivedBatchCount++;
    s_lastModelInfoTick = GetTickCount();

    auto existingIt = s_modelsByServerId.find(newId);
    if (existingIt != s_modelsByServerId.end() &&
        existingIt->second.dffCrc == dffCrc &&
        existingIt->second.txdCrc == txdCrc &&
        existingIt->second.loadState == ModelLoadState::Loaded)
    {
        // Already loaded identically from earlier batch
        return;
    }

    CustomModelEntry entry{};
    entry.type         = type;
    entry.virtualWorld = virtualWorld;
    entry.baseId       = baseId;
    entry.newId        = newId;
    entry.dffCrc       = dffCrc;
    entry.txdCrc       = txdCrc;
    entry.dffSize      = dffSize;
    entry.txdSize      = txdSize;
    entry.timeOn       = timeOn;
    entry.timeOff      = timeOff;
    entry.internalSlot = (existingIt != s_modelsByServerId.end()) ? existingIt->second.internalSlot : -1;
    entry.loadState    = ModelLoadState::WaitingFiles;

    s_modelsByServerId[newId] = entry;

    EnqueueFileIfNeeded(FILE_TYPE_DFF, dffCrc, dffSize);
    EnqueueFileIfNeeded(FILE_TYPE_TXD, txdCrc, txdSize);

    UpdateWaitingModelsForFile(FILE_TYPE_DFF, dffCrc);
    UpdateWaitingModelsForFile(FILE_TYPE_TXD, txdCrc);

    FLog("0.3.DL: ModelInfo [%u/%d] type=%u vw=%d base=%d new=%d dff=%08X(%u) txd=%08X(%u)",
         index, count, type, virtualWorld, baseId, newId, dffCrc, dffSize, txdCrc, txdSize);
}

void CCustomModels::OnModelFileRPC(RPCParameters* rpcParams)
{
    if (!rpcParams || !rpcParams->input || rpcParams->numberOfBitsOfData < 8)
        return;

    uint8_t* data = reinterpret_cast<uint8_t*>(rpcParams->input);
    int byteLen = (rpcParams->numberOfBitsOfData + 7) / 8;
    RakNet::BitStream bs(data, byteLen, false);

    uint8_t arg = 0;
    if (!bs.Read(arg)) return;

    if (arg == 1)
    {
        // Direct RakNet file transfer: UINT8 fileType, UINT32 crc, UINT32 length, char data[length]
        uint8_t  fileType = 0;
        uint32_t crc = 0;
        uint32_t length = 0;
        if (!bs.Read(fileType) || !bs.Read(crc) || !bs.Read(length))
            return;

        auto it = s_files.find(FileKey{ fileType, crc });
        if (it == s_files.end())
        {
            FLog("0.3.DL: unsolicited direct ModelFile type=%u crc=%08X", fileType, crc);
            return;
        }
        FileTask& task = it->second;

        if (length == 0 || length > kMaxModelFileSizeBytes)
        {
            FLog("0.3.DL: invalid direct ModelFile length=%u crc=%08X", length, crc);
            FinalizeFileTask(task, false);
            return;
        }

        int unreadBytes = bs.GetNumberOfUnreadBits() / 8;
        if (static_cast<uint32_t>(unreadBytes) < length)
        {
            FLog("0.3.DL: truncated ModelFile payload (need=%u, have=%d, crc=%08X)", length, unreadBytes, crc);
            FinalizeFileTask(task, false);
            return;
        }

        if (task.rakNetBuffer.size() + length > kMaxModelFileSizeBytes)
        {
            FinalizeFileTask(task, false);
            return;
        }

        size_t oldSz = task.rakNetBuffer.size();
        task.rakNetBuffer.resize(oldSz + length);
        if (!bs.Read(reinterpret_cast<char*>(task.rakNetBuffer.data() + oldSz), length))
        {
            FinalizeFileTask(task, false);
            return;
        }

        task.requestSentTick = GetTickCount();

        // Check if complete (either matches expected size or passes CRC32 directly)
        bool reachedExpected = (task.expectedSize == 0 || task.rakNetBuffer.size() >= task.expectedSize);
        uint32_t actualCrc = CalcCrc32(task.rakNetBuffer.data(), task.rakNetBuffer.size());
        if (actualCrc == task.crc)
        {
            bool written = WriteFileAtomic(task.cachePath, task.rakNetBuffer.data(), task.rakNetBuffer.size());
            FinalizeFileTask(task, written);
        }
        else if (reachedExpected)
        {
            FLog("0.3.DL: direct ModelFile CRC mismatch crc=%08X got=%08X len=%zu",
                 task.crc, actualCrc, task.rakNetBuffer.size());
            FinalizeFileTask(task, false);
        }
    }
    else if (arg == 6)
    {
        // HTTP redirect: UINT8 fileType, UINT32 crc, UINT8 urlLen, char url[urlLen]
        uint8_t  fileType = 0;
        uint32_t crc = 0;
        uint8_t  urlLen = 0;
        if (!bs.Read(fileType) || !bs.Read(crc) || !bs.Read(urlLen))
            return;

        auto it = s_files.find(FileKey{ fileType, crc });
        if (it == s_files.end())
        {
            FLog("0.3.DL: unsolicited HTTP redirect type=%u crc=%08X", fileType, crc);
            return;
        }
        FileTask& task = it->second;

        if (urlLen == 0)
        {
            FinalizeFileTask(task, false);
            return;
        }

        std::string url(urlLen, '\0');
        if (!bs.Read(&url[0], urlLen))
        {
            FinalizeFileTask(task, false);
            return;
        }

        FLog("0.3.DL: HTTP redirect %s CRC=%08X -> %s",
             (fileType == FILE_TYPE_TXD) ? "TXD" : "DFF", crc, url.c_str());

        task.state = FileState::DownloadingHttp;
        task.requestSentTick = GetTickCount();

        if (!pJavaWrapper || !pJavaWrapper->DownloadCustomModel(fileType, crc, url.c_str(), task.cachePath.c_str()))
        {
            FLog("0.3.DL: failed dispatching JNI HTTP download for CRC=%08X", crc);
            FinalizeFileTask(task, false);
        }
    }
    else
    {
        // Error / unsupported sub-op (e.g. file not found on server)
        uint8_t  fileType = 0;
        uint32_t crc = 0;
        if (bs.Read(fileType) && bs.Read(crc))
        {
            auto it = s_files.find(FileKey{ fileType, crc });
            if (it != s_files.end())
            {
                FLog("0.3.DL: server returned ModelFile arg=%u for type=%u crc=%08X", arg, fileType, crc);
                FinalizeFileTask(it->second, false);
            }
        }
    }
}

void CCustomModels::OnSrcFinishedDownloadingRPC(RPCParameters* rpcParams)
{
    (void)rpcParams;
    FLog("0.3.DL: received SrcFinishedDownloading");
    if (!s_batchActive && s_modelsByServerId.empty())
    {
        s_finishedSent = true;
    }
}

void CCustomModels::OnSetPlayerVirtualWorldRPC(RPCParameters* rpcParams)
{
    if (!rpcParams || !rpcParams->input || rpcParams->numberOfBitsOfData < 32)
        return;

    uint8_t* data = reinterpret_cast<uint8_t*>(rpcParams->input);
    int byteLen = (rpcParams->numberOfBitsOfData + 7) / 8;
    RakNet::BitStream bs(data, byteLen, false);

    int32_t vw = 0;
    if (rpcParams->numberOfBitsOfData >= 64)
    {
        int32_t playerId = 0;
        bs.Read(playerId);
        bs.Read(vw);
    }
    else
    {
        bs.Read(vw);
    }

    s_currentVirtualWorld = vw;
    FLog("0.3.DL: SetPlayerVirtualWorld -> %d", vw);
}

void CCustomModels::OnHttpDownloadFinished(uint8_t fileType, uint32_t crc, bool success)
{
    std::lock_guard<std::mutex> lock(s_httpMutex);
    s_httpResults.push_back(HttpResult{ fileType, crc, success });
}

void CCustomModels::Process(RakClientInterface* pRakClient)
{
    if (!s_initialized) return;

    // 1. Drain completed HTTP downloads from Java worker threads
    ProcessCompletedHttpDownloads();

    // 2. Check timeouts on active transfers
    CheckDownloadTimeouts();

    // 3. Dispatch pending file requests up to concurrency cap
    if (pRakClient)
    {
        size_t active = CountActiveDownloads();
        while (active < kMaxConcurrentRequests && !s_downloadQueue.empty())
        {
            FileKey key = s_downloadQueue.front();
            s_downloadQueue.pop_front();

            auto it = s_files.find(key);
            if (it == s_files.end() || it->second.state != FileState::Pending)
                continue;

            SendFileRequestRpc(pRakClient, it->second);
            ++active;
        }
    }

    // 4. Progress status in chat (rate-limited every 2.5s while downloading)
    if (s_batchActive && !s_finishedSent && pUI && pUI->chat())
    {
        size_t incomplete = CountIncompleteFiles();
        uint32_t now = GetTickCount();
        if (incomplete > 0 && (s_lastProgressChatTick == 0 || now - s_lastProgressChatTick >= 2500))
        {
            size_t total = s_files.size();
            size_t done = (total >= incomplete) ? (total - incomplete) : 0;
            pUI->chat()->addDebugMessage("{88CCFF}[0.3.DL] Downloading custom assets: %zu / %zu...", done, total);
            s_lastProgressChatTick = now;
        }
    }

    // 5. Load ready models into GTA SA engine (throttled per frame to keep UI smooth)
    int loadedThisFrame = 0;
    for (auto& kv : s_modelsByServerId)
    {
        if (loadedThisFrame >= kMaxEngineLoadsPerFrame) break;
        if (kv.second.loadState == ModelLoadState::ReadyToLoad)
        {
            LoadCustomModelIntoEngine(kv.second);
            ++loadedThisFrame;
        }
    }

    // 6. Send FinishedDownloading (RPC 184) once batch announcements, downloads, and engine loads complete
    if (s_batchActive && !s_finishedSent && pRakClient)
    {
        uint32_t now = GetTickCount();
        bool batchAnnouncementsComplete =
            (s_expectedBatchCount > 0 && s_receivedBatchCount >= s_expectedBatchCount) ||
            (s_lastModelInfoTick != 0 && (now - s_lastModelInfoTick) >= kBatchIdleGraceMs);

        if (batchAnnouncementsComplete &&
            s_downloadQueue.empty() &&
            CountActiveDownloads() == 0 &&
            CountModelsPendingLoad() == 0)
        {
            SendFinishedDownloadingRpc(pRakClient);
        }
    }
}

int32_t CCustomModels::ResolveModelId(int32_t serverModelId)
{
    if (serverModelId >= 0 && serverModelId < TOTAL_DFF_MODEL_IDS)
        return serverModelId;

    auto it = s_modelsByServerId.find(serverModelId);
    if (it == s_modelsByServerId.end())
        return serverModelId;

    const CustomModelEntry& entry = it->second;
    if (entry.loadState == ModelLoadState::Loaded &&
        entry.internalSlot >= 0 &&
        entry.internalSlot < TOTAL_DFF_MODEL_IDS)
    {
        return entry.internalSlot;
    }

    // Graceful fallback to baseId while downloading or if load failed
    return entry.baseId;
}

int32_t CCustomModels::ResolveObjectModelId(int32_t serverModelId)
{
    int32_t resolved = ResolveModelId(serverModelId);
    if (resolved < 0 || resolved >= TOTAL_DFF_MODEL_IDS)
        return kFallbackObjectModel;
    return resolved;
}

int32_t CCustomModels::ResolvePedModelId(int32_t serverModelId)
{
    if (serverModelId >= 0 && serverModelId < TOTAL_DFF_MODEL_IDS)
        return serverModelId;

    auto it = s_modelsByServerId.find(serverModelId);
    if (it != s_modelsByServerId.end())
    {
        const CustomModelEntry& entry = it->second;
        if (entry.type == MODEL_TYPE_CHAR)
        {
            if (entry.loadState == ModelLoadState::Loaded &&
                entry.internalSlot >= 0 &&
                entry.internalSlot < TOTAL_DFF_MODEL_IDS)
            {
                return entry.internalSlot;
            }
            if (entry.baseId >= 0 && entry.baseId < TOTAL_DFF_MODEL_IDS)
                return entry.baseId;
        }
    }
    return kFallbackPedModel;
}

int32_t CCustomModels::ResolveTextDrawModelId(uint16_t rawWireModelId)
{
    // 0.3.DL custom skins (20001..30000) fit directly in uint16_t
    if (rawWireModelId >= static_cast<uint16_t>(MIN_CUSTOM_SKIN_ID) &&
        rawWireModelId <= static_cast<uint16_t>(MAX_CUSTOM_SKIN_ID))
    {
        return ResolvePedModelId(static_cast<int32_t>(rawWireModelId));
    }

    // 0.3.DL custom objects (-1000..-30000) wrap into signed int16_t inside uint16_t
    int16_t signedId = static_cast<int16_t>(rawWireModelId);
    if (signedId < 0)
    {
        return ResolveObjectModelId(static_cast<int32_t>(signedId));
    }

    return static_cast<int32_t>(rawWireModelId);
}

bool CCustomModels::IsCustomSlot(int32_t internalSlot)
{
    return s_customSlots.find(internalSlot) != s_customSlots.end();
}

bool CCustomModels::IsCustomServerModel(int32_t serverModelId)
{
    return s_modelsByServerId.find(serverModelId) != s_modelsByServerId.end();
}

bool CCustomModels::IsCustomPedModel(int32_t serverModelId)
{
    auto it = s_modelsByServerId.find(serverModelId);
    return it != s_modelsByServerId.end() && it->second.type == MODEL_TYPE_CHAR;
}

bool CCustomModels::EnsureSlotLoaded(int32_t internalSlot)
{
    auto it = s_serverIdBySlot.find(internalSlot);
    if (it == s_serverIdBySlot.end()) return false;

    auto mIt = s_modelsByServerId.find(it->second);
    if (mIt == s_modelsByServerId.end()) return false;

    CStreamingInfo& info = CStreaming::GetInfo(internalSlot);
    CBaseModelInfo* mi = CModelInfo::GetModelInfo(internalSlot);
    if (info.IsLoaded() && mi && mi->m_pRwObject)
    {
        return true;
    }

    // Reload from local disk cache if RW object was somehow evicted
    mIt->second.loadState = ModelLoadState::ReadyToLoad;
    return LoadCustomModelIntoEngine(mIt->second);
}
