package org.medialab.codec;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Synchronous codec worker; never called on the UI thread. Uses platform AAC, not a custom codec. */
final class AudioPipeline {
    private static final long WAIT_US = 10000;
    private AudioPipeline() {}
    static byte[] wave(int rate, int channels, boolean silent) {
        int count = rate * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + count * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(b.capacity()-8).put(new byte[]{'W','A','V','E','f','m','t',' '});
        b.putInt(16).putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2);
        b.putShort((short)(channels*2)).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(count*channels*2);
        for (int i=0;i<count;i++) for (int c=0;c<channels;c++)
            b.putShort((short)(silent ? 0 : 12000 * Math.sin(2*Math.PI*(440+c*220)*i/rate)));
        return b.array();
    }
    private static void deadline(long end) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("codec cancelled");
        if (SystemClock.elapsedRealtime() > end) throw new IllegalStateException("codec EOS timeout");
    }
    static JSONObject run(File destination, int rate, int channels, boolean silent) throws Exception {
        byte[] wav = wave(rate, channels, silent);
        int[] metadata = WaveNative.parse(wav);
        if (metadata[0]!=rate || metadata[1]!=channels || metadata[2]!=44 || metadata[3]!=wav.length-44)
            throw new IllegalStateException("JNI WAV metadata mismatch");
        byte[] pcm = new byte[metadata[3]];
        System.arraycopy(wav, metadata[2], pcm, 0, pcm.length);
        int inputFrames=pcm.length/(channels*2);
        // API30 Codec2 AAC emits complete 1024-sample access units. Explicitly pad
        // the partial tail before EOS; preserve the original length in the report.
        int paddedFrames=((inputFrames+1023)/1024)*1024;
        byte[] padded=java.util.Arrays.copyOf(pcm,paddedFrames*channels*2);
        JSONObject encoded = encode(destination, padded, rate, channels);
        JSONObject decoded = decode(destination, rate, channels);
        int frames = decoded.getInt("frames");
        // AAC is lossy and may include encoder delay/padding; do not assert byte-identical PCM.
        if (frames != paddedFrames) throw new IllegalStateException("unexpected decoded duration: "+frames+" expected="+paddedFrames+" encoded="+encoded+" decoded="+decoded);
        double rms = decoded.getDouble("rms");
        if (silent ? rms>50 : rms<6000 || rms>10000) throw new IllegalStateException("unexpected decoded RMS: "+rms);
        encoded.put("decode", decoded).put("rate",rate).put("channels",channels).put("silent",silent)
            .put("inputFrames",inputFrames).put("paddingFrames",paddedFrames-inputFrames).put("fileBytes",destination.length()).put("status","PASS");
        return encoded;
    }
    private static JSONObject encode(File path, byte[] pcm, int rate, int channels) throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaMuxer muxer = null;
        boolean started=false, muxStarted=false, complete=false;
        int samples=0, track=-1, offset=0; long lastPts=-1;
        String codecName=codec.getName(), outputFormat="";
        try {
            MediaFormat f=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE,128000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096*channels);
            codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); started=true;
            muxer=new MediaMuxer(path.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            boolean inputEos=false, outputEos=false; long end=SystemClock.elapsedRealtime()+20000;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while (!outputEos) {
                deadline(end);
                if (!inputEos) {
                    int index=codec.dequeueInputBuffer(WAIT_US);
                    if (index>=0) {
                        ByteBuffer input=codec.getInputBuffer(index);
                        if (input==null) throw new IllegalStateException("missing encoder buffer");
                        input.clear(); int bytes=Math.min(input.remaining(),pcm.length-offset);
                        bytes-=bytes%(channels*2);
                        if (bytes==0 && offset<pcm.length) throw new IllegalStateException("encoder input smaller than PCM frame");
                        if (bytes>0) input.put(pcm,offset,bytes);
                        long pts=(offset/(channels*2))*1000000L/rate;
                        inputEos=offset==pcm.length;
                        codec.queueInputBuffer(index,0,bytes,pts,inputEos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                        offset+=bytes;
                    }
                }
                int index=codec.dequeueOutputBuffer(info,WAIT_US);
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new IllegalStateException("encoder format changed twice");
                    outputFormat=codec.getOutputFormat().toString();
                    track=muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxStarted=true;
                } else if (index>=0) {
                    try {
                        if (info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                            if (!muxStarted || info.presentationTimeUs<lastPts) throw new IllegalStateException("invalid mux state/PTS");
                            ByteBuffer output=codec.getOutputBuffer(index);
                            if (output==null) throw new IllegalStateException("missing encoder output");
                            output.position(info.offset); output.limit(info.offset+info.size);
                            muxer.writeSampleData(track,output,info); samples++; lastPts=info.presentationTimeUs;
                        }
                        outputEos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    } finally { codec.releaseOutputBuffer(index,false); }
                }
            }
            if (samples==0) throw new IllegalStateException("empty AAC stream");
            MediaCodec.BufferInfo tail=new MediaCodec.BufferInfo();
            long durationUs=(pcm.length/(channels*2))*1000000L/rate;
            tail.set(0,0,Math.max(lastPts+1,durationUs),MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            muxer.writeSampleData(track,ByteBuffer.allocate(0),tail);
            muxer.stop(); muxStarted=false; complete=true;
            return new JSONObject().put("encoder",codecName).put("encodedSamples",samples).put("lastPtsUs",lastPts).put("outputFormat",outputFormat).put("encodeEos",true);
        } finally {
            try { if (started) codec.stop(); } finally {
                try { codec.release(); } finally {
                    if (muxer!=null) { try { if (muxStarted) muxer.stop(); } finally { muxer.release(); } }
                    if (!complete && path.exists() && !path.delete()) android.util.Log.w("MediaLab","partial MP4 cleanup failed");
                }
            }
        }
    }
    static JSONObject decode(File path, int expectedRate, int expectedChannels) throws Exception {
        MediaExtractor extractor=new MediaExtractor(); MediaCodec codec=null; boolean started=false;
        try {
            extractor.setDataSource(path.getAbsolutePath()); int track=-1;
            for (int i=0;i<extractor.getTrackCount();i++) if ("audio/mp4a-latm".equals(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME))) { track=i; break; }
            if (track<0) throw new IllegalArgumentException("AAC track missing");
            extractor.selectTrack(track); MediaFormat f=extractor.getTrackFormat(track);
            f.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            codec=MediaCodec.createDecoderByType("audio/mp4a-latm"); String name=codec.getName();
            codec.configure(f,null,null,0); codec.start(); started=true;
            boolean inputEos=false,outputEos=false,formatSeen=false; long count=0,lastPts=-1; double sum=0;
            long end=SystemClock.elapsedRealtime()+20000;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while (!outputEos) {
                deadline(end);
                if (!inputEos) {
                    int index=codec.dequeueInputBuffer(WAIT_US);
                    if (index>=0) {
                        ByteBuffer input=codec.getInputBuffer(index);
                        if (input==null) throw new IllegalStateException("missing decoder buffer");
                        input.clear(); int bytes=extractor.readSampleData(input,0);
                        inputEos=bytes<0;
                        codec.queueInputBuffer(index,0,Math.max(0,bytes),inputEos?0:extractor.getSampleTime(),inputEos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                        if (!inputEos) extractor.advance();
                    }
                }
                int index=codec.dequeueOutputBuffer(info,WAIT_US);
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out=codec.getOutputFormat();
                    int encoding=out.containsKey(MediaFormat.KEY_PCM_ENCODING)?out.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if (out.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=expectedRate || out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=expectedChannels || encoding!=AudioFormat.ENCODING_PCM_16BIT)
                        throw new IllegalStateException("unexpected decoded PCM format: "+out);
                    formatSeen=true;
                } else if (index>=0) {
                    try {
                        if (info.size>0) {
                            if (!formatSeen || info.size%(2*expectedChannels)!=0 || info.presentationTimeUs<lastPts) throw new IllegalStateException("invalid PCM/PTS");
                            ByteBuffer output=codec.getOutputBuffer(index);
                            if (output==null) throw new IllegalStateException("missing decoder output");
                            output.order(ByteOrder.LITTLE_ENDIAN).position(info.offset); output.limit(info.offset+info.size);
                            while (output.remaining()>=2) { double sample=output.getShort(); sum+=sample*sample; count++; }
                            lastPts=info.presentationTimeUs;
                        }
                        outputEos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    } finally { codec.releaseOutputBuffer(index,false); }
                }
            }
            if (count==0) throw new IllegalStateException("empty decoded PCM");
            return new JSONObject().put("decoder",name).put("frames",count/expectedChannels).put("rms",Math.sqrt(sum/count)).put("decodeEos",true);
        } finally {
            try { if (codec!=null) { try { if (started) codec.stop(); } finally { codec.release(); } } }
            finally { extractor.release(); }
        }
    }
}
