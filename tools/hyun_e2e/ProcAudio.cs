// 현비서 E2E 영상 소리 — 한 프로세스(에뮬 qemu)가 내는 소리만 녹음한다(Windows 프로세스 루프백, Win10 20348+ / Win11).
// PC 전체 소리를 녹음하면 다른 앱(동영상 플레이어·메신저 알림) 소리가 섞인다(11-08 실측) → 에뮬 프로세스 트리만.
//
//   csc /nologo /optimize /platform:x64 /out:ProcAudio.exe ProcAudio.cs     (run.py가 처음 쓸 때 .NET Framework csc로 빌드)
//   ProcAudio.exe <pid> <out.wav> [최대 초]
//     → 표준 출력 첫 줄 "START <epoch 초>" (wav 0번 샘플의 시각) · 표준 입력에 한 줄(또는 닫힘)이 오면 멈추고 wav를 닫는다
// 48kHz 16bit 스테레오. 조용한 동안 패킷이 안 와도 QPC 시각으로 자리를 맞춰 0으로 채운다(영상과 시각을 맞추려고).
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace HyunE2E
{
    [StructLayout(LayoutKind.Sequential)]
    struct ActivationParams { public int ActivationType; public uint TargetProcessId; public int ProcessLoopbackMode; }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    struct PropVariantBlob { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public uint cbSize; [FieldOffset(16)] public IntPtr pBlobData; }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceAsyncOperation
    {
        void GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceCompletionHandler
    {
        void ActivateCompleted(IActivateAudioInterfaceAsyncOperation activateOperation);
    }

    [ComImport, Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAgileObject { }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint streamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, IntPtr audioSessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferSize);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr closestMatch);
        [PreserveSig] int GetMixFormat(out IntPtr deviceFormat);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService([MarshalAs(UnmanagedType.LPStruct)] Guid interfaceId, [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint numFrames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint numFrames);
        [PreserveSig] int GetNextPacketSize(out uint numFrames);
    }

    class Done : IActivateAudioInterfaceCompletionHandler, IAgileObject
    {
        public readonly ManualResetEvent Event = new ManualResetEvent(false);
        public IActivateAudioInterfaceAsyncOperation Op;
        public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation op) { Op = op; Event.Set(); }
    }

    static class Program
    {
        [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = false)]
        static extern void ActivateAudioInterfaceAsync([MarshalAs(UnmanagedType.LPWStr)] string path, [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
            IntPtr activationParams, IActivateAudioInterfaceCompletionHandler handler, out IActivateAudioInterfaceAsyncOperation op);

        const int RATE = 48000, CH = 2, BYTES = 2, FRAME = CH * BYTES;

        static double Now100ns() { return (double)Stopwatch.GetTimestamp() * 1e7 / Stopwatch.Frequency; }

        [MTAThread]
        static int Main(string[] args)
        {
            if (args.Length < 2) { Console.Error.WriteLine("ProcAudio <pid> <out.wav> [maxSec]"); return 2; }
            uint pid = uint.Parse(args[0]);
            string outPath = args[1];
            double maxSec = args.Length > 2 ? double.Parse(args[2]) : 3600;

            var ap = new ActivationParams { ActivationType = 1 /*PROCESS_LOOPBACK*/, TargetProcessId = pid, ProcessLoopbackMode = 0 /*INCLUDE_TARGET_PROCESS_TREE*/ };
            IntPtr apPtr = Marshal.AllocHGlobal(Marshal.SizeOf(ap));
            Marshal.StructureToPtr(ap, apPtr, false);
            var pv = new PropVariantBlob { vt = 65 /*VT_BLOB*/, cbSize = (uint)Marshal.SizeOf(ap), pBlobData = apPtr };
            IntPtr pvPtr = Marshal.AllocHGlobal(Marshal.SizeOf(pv));
            Marshal.StructureToPtr(pv, pvPtr, false);

            var done = new Done();
            IActivateAudioInterfaceAsyncOperation op;
            ActivateAudioInterfaceAsync("VAD\\Process_Loopback", typeof(IAudioClient).GUID, pvPtr, done, out op);
            if (!done.Event.WaitOne(10000)) { Console.Error.WriteLine("activate timeout"); return 3; }
            int hr; object obj;
            done.Op.GetActivateResult(out hr, out obj);
            if (hr != 0) { Console.Error.WriteLine("activate hr=0x" + hr.ToString("X8")); return 3; }
            var client = (IAudioClient)obj;

            // WAVEFORMATEX(PCM) — 프로세스 루프백은 GetMixFormat이 안 되므로 형식을 직접 준다
            IntPtr fmt = Marshal.AllocHGlobal(18);
            Marshal.WriteInt16(fmt, 0, 1); Marshal.WriteInt16(fmt, 2, CH); Marshal.WriteInt32(fmt, 4, RATE);
            Marshal.WriteInt32(fmt, 8, RATE * FRAME); Marshal.WriteInt16(fmt, 12, FRAME); Marshal.WriteInt16(fmt, 14, BYTES * 8); Marshal.WriteInt16(fmt, 16, 0);
            uint flags = 0x00020000 /*LOOPBACK*/ | 0x00040000 /*EVENTCALLBACK*/ | 0x80000000 /*AUTOCONVERTPCM*/ | 0x08000000 /*SRC_DEFAULT_QUALITY*/;
            hr = client.Initialize(0, flags, 200000, 0, fmt, IntPtr.Zero);
            if (hr != 0) { Console.Error.WriteLine("init hr=0x" + hr.ToString("X8")); return 4; }
            var evt = new AutoResetEvent(false);
            client.SetEventHandle(evt.SafeWaitHandle.DangerousGetHandle());
            object capObj;
            hr = client.GetService(typeof(IAudioCaptureClient).GUID, out capObj);
            if (hr != 0) { Console.Error.WriteLine("service hr=0x" + hr.ToString("X8")); return 4; }
            var cap = (IAudioCaptureClient)capObj;

            var fs = new FileStream(outPath, FileMode.Create, FileAccess.ReadWrite);
            fs.Write(new byte[44], 0, 44);
            long end = 0;   // 쓴 끝(프레임)
            bool stop = false;
            var reader = new Thread(() => { try { Console.In.ReadLine(); } catch { } stop = true; });
            reader.IsBackground = true;

            double t0 = Now100ns();
            double epoch0 = (DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalSeconds;
            hr = client.Start();
            if (hr != 0) { Console.Error.WriteLine("start hr=0x" + hr.ToString("X8")); return 5; }
            Console.WriteLine("START " + epoch0.ToString("F3", System.Globalization.CultureInfo.InvariantCulture));
            Console.Out.Flush();
            reader.Start();

            byte[] buf = new byte[0];
            int packets = 0;
            while (!stop && (Now100ns() - t0) / 1e7 < maxSec)
            {
                evt.WaitOne(100);
                uint n;
                while (cap.GetNextPacketSize(out n) == 0 && n > 0)
                {
                    IntPtr data; uint frames, f; ulong devPos, qpc;
                    if (cap.GetBuffer(out data, out frames, out f, out devPos, out qpc) != 0) break;
                    int nb = (int)frames * FRAME;
                    if (buf.Length < nb) buf = new byte[nb];
                    if ((f & 0x2) != 0) Array.Clear(buf, 0, nb);   // AUDCLNT_BUFFERFLAGS_SILENT
                    else Marshal.Copy(data, buf, 0, nb);
                    cap.ReleaseBuffer(frames);
                    // 이 패킷 첫 프레임의 시각(QPC, 100ns) → wav 자리. 앞과 20ms 넘게 비면 그만큼 0을 두고, 겹치면 이어 붙인다
                    long at = qpc != 0 ? (long)((qpc - t0) * RATE / 1e7) : end;
                    if (packets++ < 3) Console.Error.WriteLine("packet frames=" + frames + " qpcAt=" + at + " end=" + end + " flags=" + f);
                    if (at < 0) at = 0;
                    if (at - end > RATE / 50) end = at;
                    fs.Seek(44 + end * FRAME, SeekOrigin.Begin);
                    fs.Write(buf, 0, nb);
                    end += frames;
                }
            }
            client.Stop();
            // 멈춘 시각까지 0으로 채워 길이를 맞춘다
            long total = Math.Max(end, (long)((Now100ns() - t0) * RATE / 1e7));
            fs.SetLength(44 + total * FRAME);
            fs.Seek(0, SeekOrigin.Begin);
            var w = new BinaryWriter(fs);
            w.Write(new[] { (byte)'R', (byte)'I', (byte)'F', (byte)'F' }); w.Write((int)(36 + total * FRAME));
            w.Write(new[] { (byte)'W', (byte)'A', (byte)'V', (byte)'E' }); w.Write(new[] { (byte)'f', (byte)'m', (byte)'t', (byte)' ' });
            w.Write(16); w.Write((short)1); w.Write((short)CH); w.Write(RATE); w.Write(RATE * FRAME); w.Write((short)FRAME); w.Write((short)(BYTES * 8));
            w.Write(new[] { (byte)'d', (byte)'a', (byte)'t', (byte)'a' }); w.Write((int)(total * FRAME));
            w.Flush(); fs.Close();
            Console.WriteLine("FRAMES " + total + " PACKETS " + packets);
            return 0;
        }
    }
}
