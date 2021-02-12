/*
*      _______                       _____   _____ _____  
*     |__   __|                     |  __ \ / ____|  __ \ 
*        | | __ _ _ __ ___  ___  ___| |  | | (___ | |__) |
*        | |/ _` | '__/ __|/ _ \/ __| |  | |\___ \|  ___/ 
*        | | (_| | |  \__ \ (_) \__ \ |__| |____) | |     
*        |_|\__,_|_|  |___/\___/|___/_____/|_____/|_|     
*                                                         
* -------------------------------------------------------------
*
* TarsosDSP is developed by Joren Six at IPEM, University Ghent
*  
* -------------------------------------------------------------
*
*  Info: http://0110.be/tag/TarsosDSP
*  Github: https://github.com/JorenSix/TarsosDSP
*  Releases: http://0110.be/releases/TarsosDSP/
*  
*  TarsosDSP includes modified source code by various authors,
*  for credits and info, see README.
* 
*/

/******************************************************************************
 *
 * libresample4j
 * Copyright (c) 2009 Laszlo Systems, Inc. All Rights Reserved.
 *
 * libresample4j is a Java port of Dominic Mazzoni's libresample 0.1.3,
 * which is in turn based on Julius Smith's Resample 1.7 library.
 *      http://www-ccrma.stanford.edu/~jos/resample/
 *
 * License: LGPL -- see the file LICENSE.txt for more information
 *
 *****************************************************************************/
package priv.droptea.emotion.resample;

import java.nio.FloatBuffer;
//参考资料https://ccrma.stanford.edu/~jos/resample/Implementation.html
public class Resampler {

    public static class Result {
        public final int inputSamplesConsumed;
        public final int outputSamplesGenerated;

        public Result(int inputSamplesConsumed, int outputSamplesGenerated) {
            this.inputSamplesConsumed = inputSamplesConsumed;
            this.outputSamplesGenerated = outputSamplesGenerated;
        }
    }

    // number of values per 1/delta in impulse response
    protected static final int Npc = 4096;

    private final float[] Imp;
    private final float[] ImpD;
    private final float LpScl;
    private final int Nmult;
    private final int Nwing;
    private final double minFactor;
    private final double maxFactor;
    private final int XSize;
    private final float[] X;
    private int Xp; // Current "now"-sample pointer for input
    private int Xread; // Position to put new samples
    private final int Xoff;
    private final float[] Y;
    private int Yp;
    private double Time;

    /**
     * Clone an existing resampling session. Faster than creating one from scratch.
     *
     * @param other
     */
    public Resampler(Resampler other) {
        this.Imp = other.Imp.clone();
        this.ImpD = other.ImpD.clone();
        this.LpScl = other.LpScl;
        this.Nmult = other.Nmult;
        this.Nwing = other.Nwing;
        this.minFactor = other.minFactor;
        this.maxFactor = other.maxFactor;
        this.XSize = other.XSize;
        this.X = other.X.clone();
        this.Xp = other.Xp;
        this.Xread = other.Xread;
        this.Xoff = other.Xoff;
        this.Y = other.Y.clone();
        this.Yp = other.Yp;
        this.Time = other.Time;
    }

    /**
     * Create a new resampling session.
     *
     * @param highQuality true for better quality, slower processing time
     * @param minFactor   lower bound on resampling factor for this session
     * @param maxFactor   upper bound on resampling factor for this session
     * @throws IllegalArgumentException if minFactor or maxFactor is not
     *                                  positive, or if maxFactor is less than minFactor
     */
    public Resampler(boolean highQuality, double minFactor, double maxFactor) {
        if (minFactor <= 0.0 || maxFactor <= 0.0) {
            throw new IllegalArgumentException("minFactor and maxFactor must be positive");
        }
        if (maxFactor < minFactor) {
            throw new IllegalArgumentException("minFactor must be <= maxFactor");
        }

        this.minFactor = minFactor;
        this.maxFactor = maxFactor;
        this.Nmult = highQuality ? 35 : 11;
        
        this.LpScl = 1.0f;
        //凯泽窗一半的长度
        this.Nwing = Npc * (this.Nmult - 1) / 2; // # of filter coeffs in right wing

        double Rolloff = 0.90;
        //定义凯泽窗的Beta系数值
        double Beta = 6;
        //用凯泽窗设计的滤波器的系数数组，其实就是窗函数的Y值，每一项与时域信号的振幅一一相乘后起到抗混叠滤波的作用
        
        double[] Imp64 = new double[this.Nwing];
        //传入参数初始化凯泽窗设计的录波器，把窗函数的值填充到Imp64里
        FilterKit.lrsLpFilter(Imp64, this.Nwing, 0.5 * Rolloff, Beta, Npc);
        
        this.Imp = new float[this.Nwing];
        this.ImpD = new float[this.Nwing];
        //存储Imp64到Imp中
        for (int i = 0; i < this.Nwing; i++) {
            this.Imp[i] = (float) Imp64[i];
        }
        //保存每项与下一项的差值，这个值将来会用来做线性差值使用
        for (int i = 0; i < this.Nwing - 1; i++) {
            this.ImpD[i] = this.Imp[i + 1] - this.Imp[i];
        }
        // Last coeff. not interpolated
        this.ImpD[this.Nwing - 1] = -this.Imp[this.Nwing - 1];
        //LP是低通的缩写
        // Calc reach of LP filter wing (plus some creeping room)
        int Xoff_min = (int) (((this.Nmult + 1) / 2.0) * Math.max(1.0, 1.0 / minFactor) + 10);
        int Xoff_max = (int) (((this.Nmult + 1) / 2.0) * Math.max(1.0, 1.0 / maxFactor) + 10);
        this.Xoff = Math.max(Xoff_min, Xoff_max);

        // Make the inBuffer size at least 4096, but larger if necessary
        // in order to store the minimum reach of the LP filter and then some.
        // Then allocate the buffer an extra Xoff larger so that
        // we can zero-pad up to Xoff zeros at the end when we reach the
        // end of the input samples.
        //滤波和重采样是同时进行的，每次处理的数据根据窗函数的设计有大小要求，这里定义了一次处理数据的大小
        this.XSize = Math.max(2 * this.Xoff + 10, 4096);
        this.X = new float[this.XSize + this.Xoff];
        this.Xp = this.Xoff;
        this.Xread = this.Xoff;

        // Make the outBuffer long enough to hold the entire processed
        // output of one inBuffer
        //这是滤波和重采样处理后的数据大小，大小根据重采样参数factor变化，factory大于1表示上采样，小于1表示下采样
        int YSize = (int) (((double) this.XSize) * maxFactor + 2.0);
        this.Y = new float[YSize];
        //多出来的处理后数据
        this.Yp = 0;

        this.Time = (double) this.Xoff; // Current-time pointer for converter
    }

    public int getFilterWidth() {
        return this.Xoff;
    }

    /**
     * Process a batch of samples. There is no guarantee that the input buffer will be drained.
     *
     * @param factor    factor at which to resample this batch
     * @param buffers   sample buffer for producing input and consuming output
     * @param lastBatch true if this is known to be the last batch of samples
     * @return true iff resampling is complete (ie. no input samples consumed and no output samples produced)
     */
    public boolean process(double factor, SampleBuffers buffers, boolean lastBatch) {
        if (factor < this.minFactor || factor > this.maxFactor) {
            throw new IllegalArgumentException("factor " + factor + " is not between minFactor=" + minFactor
                    + " and maxFactor=" + maxFactor);
        }
        //输出数据的大小
        int outBufferLen = buffers.getOutputBufferLength();
        //输入数据的大小,也就是待处理的数据大小
        int inBufferLen = buffers.getInputBufferLength();
        
        //滤波器的系数数组，就是窗函数的Y值，每一项和时域信号的振幅一一相乘，起到抗混叠滤波的作用
        float[] Imp = this.Imp;
        //使得滤波器系数的线性插值更快
        float[] ImpD = this.ImpD;
        float LpScl = this.LpScl;
        //窗函数数组一半的长度
        int Nwing = this.Nwing;
        //true表示使用差值滤波器进行重采样
        boolean interpFilt = false; // TRUE means interpolate filter coeffs
       
        //如果上次重采样后有多的输出数据Yp没有填充到上次的输出数组中，就把这些数据先填充到本次的输出数组里
        // Start by copying any samples still in the Y buffer to the output
        // buffer
        if ((this.Yp != 0) && buffers.getOutputBufferLength()> 0) {
        	
            int len = Math.min(buffers.getOutputBufferLength(), this.Yp);
            /*System.out.println("____outBufferLen:"+outBufferLen
        			+"_outSampleCount:"+outSampleCount
        			+"_Yp:"+this.Yp
        			+"_len:"+len);*/
            buffers.consumeOutput(this.Y, 0, len);
            for (int i = 0; i < this.Yp - len; i++) {
                this.Y[i] = this.Y[i + len];
            }
            this.Yp -= len;
            //System.out.println("Yp:"+this.Yp);
        }
        //如果本次的输出数组被填充满后Yp还有剩余，那就直接结束这次的重采样
        // If there are still output samples left, return now - we need
        // the full output buffer available to us...
        if (this.Yp != 0) {
            return buffers.getInputBufferLength() == inBufferLen 
            		&& buffers.getOutputBufferLength() == outBufferLen;
        }
        //LpScl是用来规格化的变量，当factor小于1时，由于下采样的加窗导致了振幅的提高，所以采样结束后，时域信号需要乘上这个变量把振幅降回来。
        // Account for increased filter gain when using factors less than 1
        if (factor < 1) {
            LpScl = (float) (LpScl * factor);
        }

        while (true) {
        	System.out.println("this.Xread"+this.Xread+"buffers.getInputBufferLength()"+buffers.getInputBufferLength());
        	//Xread是用来记录X数组已经存储的数据的大小，Xread之所以初始化为Xoff，是为了让第一次循环也能进行后面的卷积操作
            //最初的这段xoff实际上相当于填充了参与左翼卷积的，后面不用对是否是第一次循环进行判断，使得代码更简洁。
        	//XSize是X数组的长度加上一个Xoff，但我觉得不用加Xoff也可以，不过多了也不影响
        	//len就是本次循环计划从输入数据中取出的数据大小，如果输入数据足够的话，len就等于X数组去掉Xread后剩下可存储的大小，不够了就把输入数据都去取出来
            int len = Math.min(this.XSize - this.Xread, buffers.getInputBufferLength());
            
            //取出len长度的数据放到X中,从X数组index等于Xread的位置开始放,
            buffers.produceInput(this.X, this.Xread, len);
            
            this.Xread += len;

            //Nx表示卷积运算结果的index长度，例如当time为xoff时，用的是X数组index为0到2xoff的数据与凯撒窗函数进行卷积运算，
            //最终卷积运算求出来的结果是Y(2xoff)的值，所以这段Xread长度的数据求出来Y的值的范围是2xoff到Xread.所以Y的长度是Nx=Xread-2xoff
            //time被初始化为xoff,是为了保证左翼的完整性，也就是time-xoff不会小于0而导致数据不存在。
            int Nx;
            if (lastBatch && (buffers.getInputBufferLength() == 0)) {
                // If these are the last samples, zero-pad the
                // end of the input buffer and make sure we process
                // all the way to the end
                Nx = this.Xread - this.Xoff;
                for (int i = 0; i < this.Xoff; i++) {
                    this.X[this.Xread + i] = 0;
                }
            } else {
                Nx = this.Xread - 2 * this.Xoff;
            }
            System.out.println("len:"+len+"_Nx:"+Nx
            		+"_Xsize"+this.XSize+"_Xread:"+this.Xread
            		+"_Xoff:"+this.Xoff+"_Nmult"+this.Nmult
            		);
         
            if (Nx <= 0) {
                break;
            }

            // Resample stuff in input buffer
            int Nout;
            if (factor >= 1) { // SrcUp() is faster if we can use it */
                Nout = lrsSrcUp(this.X, this.Y, factor, /* &this.Time, */Nx, Nwing, LpScl, Imp, ImpD, interpFilt);
            } else {
                Nout = lrsSrcUD(this.X, this.Y, factor, /* &this.Time, */Nx, Nwing, LpScl, Imp, ImpD, interpFilt);
            }

            /*
             * #ifdef DEBUG
             * printf("Nout: %d\n", Nout);
             * #endif
             */
            //把Time的值还原回xoff，其实这样写不能真正还原回去，下面会说
            this.Time -= Nx; // Move converter Nx samples back in time
            //Xp用来记录已处理的输入数据的index位置，其实这样也不能完全表示已处理数据的位置，下面会说
            this.Xp += Nx; // Advance by number of samples processed
            //因为上面的lrsSrcUp和lrsSrcUD不能保证计算结束后，this.Time就是刚好增加了Nx（感觉好像可以把这个多出来的问题解决掉）
            //所以下面的操作就是把this.Time真正的还原回xoff,并且把多的加到Xp里去
            // Calc time accumulation in Time
            int Ncreep = (int) (this.Time) - this.Xoff;
            System.out.println("Ncreep"+Ncreep);
            if (Ncreep != 0) {
                this.Time -= Ncreep; // Remove time accumulation
                this.Xp += Ncreep; // and add it to read pointer
            }
            //相当于把本次取出的输入数据的最后2Xoff的数据保存到下一次作为卷积运算使用
            // Copy part of input signal that must be re-used
            int Nreuse = this.Xread - (this.Xp - this.Xoff);

            for (int i = 0; i < Nreuse; i++) {
                this.X[i] = this.X[i + (this.Xp - this.Xoff)];
            }
            System.out.println("Nreuse"+Nreuse);
            /*
            #ifdef DEBUG
            printf("New Xread=%d\n", Nreuse);
            #endif */

            this.Xread = Nreuse; // Pos in input buff to read new data into
            this.Xp = this.Xoff;

            this.Yp = Nout;
            //尽可能的把处理后的数据Y都保存到输出数据数组中
            // Copy as many samples as possible to the output buffer
            if (this.Yp != 0 && buffers.getOutputBufferLength() > 0) {
                len = Math.min(buffers.getOutputBufferLength(), this.Yp);
                buffers.consumeOutput(this.Y, 0, len);
                for (int i = 0; i < this.Yp - len; i++) {
                    this.Y[i] = this.Y[i + len];
                }
                this.Yp -= len;
            }

            // If there are still output samples left, return now,
            //   since we need the full output buffer available
            //如果输出数据数组存不下了，剩下的Yp就留到下一次再处理
            if (this.Yp != 0) {
            	System.out.println("Yp"+Yp);
                break;
            }
        }

        return buffers.getInputBufferLength() == inBufferLen 
        		&& buffers.getOutputBufferLength() == outBufferLen;
    }

    /**
     * Process a batch of samples. Convenience method for when the input and output are both floats.
     *
     * @param factor       factor at which to resample this batch
     * @param inputBuffer  contains input samples in the range -1.0 to 1.0
     * @param outputBuffer output samples will be deposited here
     * @param lastBatch    true if this is known to be the last batch of samples
     * @return true iff resampling is complete (ie. no input samples consumed and no output samples produced)
     */
    public boolean process(double factor, final FloatBuffer inputBuffer, boolean lastBatch, final FloatBuffer outputBuffer) {
        //初始化一个用于取出输入数据和保存输出数据的对象
    	SampleBuffers sampleBuffers = new SampleBuffers() {
        	//获取待处理的输入数据长度
            public int getInputBufferLength() {
                return inputBuffer.remaining();
            }
            //获取已处理的输出数据长度
            public int getOutputBufferLength() {
                return outputBuffer.remaining();
            }
            //从输入数据数组中取出待处理的数据
            public void produceInput(float[] array, int offset, int length) {
                inputBuffer.get(array, offset, length);
            }
            //保存处理后的数据到输出数据数组中
            public void consumeOutput(float[] array, int offset, int length) {
                outputBuffer.put(array, offset, length);
            }
        };
        return process(factor, sampleBuffers, lastBatch);
    }

    /**
     * Process a batch of samples. Alternative interface if you prefer to work with arrays.
     *
     * @param factor         resampling rate for this batch
     * @param inBuffer       array containing input samples in the range -1.0 to 1.0
     * @param inBufferOffset offset into inBuffer at which to start processing
     * @param inBufferLen    number of valid elements in the inputBuffer
     * @param lastBatch      pass true if this is the last batch of samples
     * @param outBuffer      array to hold the resampled data
     * @param outBufferOffset Offset in the output buffer.
     * @param outBufferLen    Output buffer length.
     * @return the number of samples consumed and generated
     */
    //r.process(factor, src, 0, src.length, false, out, 0, out.length);
    public Result process(double factor, float[] inBuffer, int inBufferOffset, int inBufferLen, boolean lastBatch, float[] outBuffer, int outBufferOffset, int outBufferLen) {
        FloatBuffer inputBuffer = FloatBuffer.wrap(inBuffer, inBufferOffset, inBufferLen);
        FloatBuffer outputBuffer = FloatBuffer.wrap(outBuffer, outBufferOffset, outBufferLen);

        process(factor, inputBuffer, lastBatch, outputBuffer);

        return new Result(inputBuffer.position() - inBufferOffset, outputBuffer.position() - outBufferOffset);
    }



    /*
     * Sampling rate up-conversion only subroutine; Slightly faster than
     * down-conversion;
     */
    private int lrsSrcUp(float X[], float Y[], double factor, int Nx, int Nwing, float LpScl, float Imp[],
                         float ImpD[], boolean Interp) {

        float[] Xp_array = X;
        int Xp_index;

        float[] Yp_array = Y;
        int Yp_index = 0;

        float v;

        double CurrentTime = this.Time;
        double dt; // Step through input signal
        double endTime; // When Time reaches EndTime, return to user

        dt = 1.0 / factor; // Output sampling period

        endTime = CurrentTime + Nx;
        while (CurrentTime < endTime) {
            double LeftPhase = CurrentTime - Math.floor(CurrentTime);
            double RightPhase = 1.0 - LeftPhase;

            Xp_index = (int) CurrentTime; // Ptr to current input sample
            // Perform left-wing inner product
            v = FilterKit.lrsFilterUp(Imp, ImpD, Nwing, Interp, Xp_array, Xp_index++, LeftPhase, -1);
            // Perform right-wing inner product
            v += FilterKit.lrsFilterUp(Imp, ImpD, Nwing, Interp, Xp_array, Xp_index, RightPhase, 1);

            v *= LpScl; // Normalize for unity filter gain

            Yp_array[Yp_index++] = v; // Deposit output
            CurrentTime += dt; // Move to next sample by time increment
        }

        this.Time = CurrentTime;
        return Yp_index; // Return the number of output samples
    }

    private int lrsSrcUD(float X[], float Y[], double factor, int Nx, int Nwing, float LpScl, float Imp[],
                         float ImpD[], boolean Interp) {

        float[] Xp_array = X;
        int Xp_index;

        float[] Yp_array = Y;
        int Yp_index = 0;

        float v;

        double CurrentTime = this.Time;
        double dh; // Step through filter impulse response
        double dt; // Step through input signal
        double endTime; // When Time reaches EndTime, return to user

        dt = 1.0 / factor; // Output sampling period

        dh = Math.min(Npc, factor * Npc); // Filter sampling period

        endTime = CurrentTime + Nx;
        while (CurrentTime < endTime) {
            double LeftPhase = CurrentTime - Math.floor(CurrentTime);
            double RightPhase = 1.0 - LeftPhase;

            Xp_index = (int) CurrentTime; // Ptr to current input sample
            // Perform left-wing inner product
            v = FilterKit.lrsFilterUD(Imp, ImpD, Nwing, Interp, Xp_array, Xp_index++, LeftPhase, -1, dh);
            // Perform right-wing inner product
            v += FilterKit.lrsFilterUD(Imp, ImpD, Nwing, Interp, Xp_array, Xp_index, RightPhase, 1, dh);

            v *= LpScl; // Normalize for unity filter gain

            Yp_array[Yp_index++] = v; // Deposit output

            CurrentTime += dt; // Move to next sample by time increment
        }

        this.Time = CurrentTime;
        return Yp_index; // Return the number of output samples
    }

}
