//
// Created by x1y2z on 20.03.2023.
//

#ifndef LIVERUSSIA_CSTACKTRACE_H
#define LIVERUSSIA_CSTACKTRACE_H

#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <unwind.h>
#include "../main.h"

#if VER_x32
#define PRINT_CRASH_STATES(context) \
	FLog("register states:"); \
	FLog("r0: 0x%X, r1: 0x%X, r2: 0x%X, r3: 0x%X", (context)->uc_mcontext.arm_r0, (context)->uc_mcontext.arm_r1, (context)->uc_mcontext.arm_r2, (context)->uc_mcontext.arm_r3); \
	FLog("r4: 0x%x, r5: 0x%x, r6: 0x%x, r7: 0x%x", (context)->uc_mcontext.arm_r4, (context)->uc_mcontext.arm_r5, (context)->uc_mcontext.arm_r6, (context)->uc_mcontext.arm_r7); \
	FLog("r8: 0x%x, r9: 0x%x, sl: 0x%x, fp: 0x%x", (context)->uc_mcontext.arm_r8, (context)->uc_mcontext.arm_r9, (context)->uc_mcontext.arm_r10, (context)->uc_mcontext.arm_fp); \
	FLog("ip: 0x%x, sp: 0x%x, lr: 0x%x, pc: 0x%x", (context)->uc_mcontext.arm_ip, (context)->uc_mcontext.arm_sp, (context)->uc_mcontext.arm_lr, (context)->uc_mcontext.arm_pc); \
	CStackTrace::printAddress("PC", (context)->uc_mcontext.arm_pc); \
	CStackTrace::printAddress("LR", (context)->uc_mcontext.arm_lr); \
	FLog("1: libGTASA.so + 0x%X | libsamp.so + 0x%X", (context)->uc_mcontext.arm_pc - g_libGTASA, (context)->uc_mcontext.arm_pc - g_libSAMP); \
	FLog("2: libGTASA.so + 0x%X | libsamp.so + 0x%X", (context)->uc_mcontext.arm_lr - g_libGTASA, (context)->uc_mcontext.arm_lr - g_libSAMP);
#else
#define PRINT_CRASH_STATES(context) \
	CStackTrace::printAddress("PC", (context)->uc_mcontext.pc); \
	CStackTrace::printAddress("LR", (context)->uc_mcontext.regs[30]); \
	FLog("1: libGTASA.so + 0x%llx | libsamp.so + 0x%llx", (unsigned long long)((context)->uc_mcontext.pc - g_libGTASA), (unsigned long long)((context)->uc_mcontext.pc - g_libSAMP)); \
	FLog("2: libGTASA.so + 0x%llx | libsamp.so + 0x%llx", (unsigned long long)((context)->uc_mcontext.regs[30] - g_libGTASA), (unsigned long long)((context)->uc_mcontext.regs[30] - g_libSAMP));
#endif

void FLog(const char* fmt, ...);
extern int g_iLastRenderedObject;

class CStackTrace
{
public:
    static const char* shortLibName(const char* path)
    {
        if (!path || !path[0]) return "unknown";
        const char* slash = strrchr(path, '/');
        return slash ? (slash + 1) : path;
    }

    static void printAddress(const char* label, uintptr_t pc)
    {
        Dl_info info{};
        if (pc && dladdr(reinterpret_cast<void*>(pc), &info))
        {
            uintptr_t off = info.dli_fbase ? (pc - reinterpret_cast<uintptr_t>(info.dli_fbase)) : 0;
            FLog("%s: 0x%lx [%s+0x%lx] %s",
                 label,
                 static_cast<unsigned long>(pc),
                 shortLibName(info.dli_fname),
                 static_cast<unsigned long>(off),
                 info.dli_sname ? info.dli_sname : "(no sym)");
        }
        else
        {
            FLog("%s: 0x%lx [unknown] samp:+0x%lx gta:+0x%lx",
                 label,
                 static_cast<unsigned long>(pc),
                 static_cast<unsigned long>(pc - g_libSAMP),
                 static_cast<unsigned long>(pc - g_libGTASA));
        }
    }

    static void printBacktrace()
    {
        FLog("Last rendered object: %d", g_iLastRenderedObject);
        FLog("------------ START BACKTRACE ------------");
        TraceState state{};
        _Unwind_Backtrace(TraceFunction, &state);
        if (state.stoppedReason)
        {
            FLog("(%s)", state.stoppedReason);
        }
        FLog("------------ END BACKTRACE ------------");
    }

private:
    struct TraceState
    {
        int count = 0;
        uintptr_t prevPc = 0;
        int samePcCount = 0;
        const char* stoppedReason = nullptr;
    };

    static _Unwind_Reason_Code TraceFunction(_Unwind_Context* context, void* arg)
    {
        auto* state = static_cast<TraceState*>(arg);
        uintptr_t pc = _Unwind_GetIP(context);

        if (pc == 0)
        {
            state->stoppedReason = "null pc";
            return _URC_END_OF_STACK;
        }
        if (state->count >= 32)
        {
            state->stoppedReason = "truncated at 32 frames";
            return _URC_END_OF_STACK;
        }
        if (pc == state->prevPc)
        {
            ++state->samePcCount;
            if (state->samePcCount >= 2)
            {
                state->stoppedReason = "stopped repeating unwind frame";
                return _URC_END_OF_STACK;
            }
        }
        else
        {
            state->prevPc = pc;
            state->samePcCount = 0;
        }

        int idx = state->count++;
        Dl_info info{};
        if (dladdr(reinterpret_cast<void*>(pc), &info))
        {
            uintptr_t off = info.dli_fbase ? (pc - reinterpret_cast<uintptr_t>(info.dli_fbase)) : 0;
            FLog("#%02d [0x%lx %s+0x%lx] %s",
                 idx,
                 static_cast<unsigned long>(pc),
                 shortLibName(info.dli_fname),
                 static_cast<unsigned long>(off),
                 info.dli_sname ? info.dli_sname : "(no sym)");
        }
        else
        {
            FLog("#%02d [0x%lx samp:+0x%lx gta:+0x%lx] (no lib)",
                 idx,
                 static_cast<unsigned long>(pc),
                 static_cast<unsigned long>(pc - g_libSAMP),
                 static_cast<unsigned long>(pc - g_libGTASA));
        }

        return _URC_NO_REASON;
    }
};

#endif //LIVERUSSIA_CSTACKTRACE_H
