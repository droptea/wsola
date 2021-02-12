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

package priv.droptea.emotion.resample;

import priv.droptea.emotion.AudioEvent;
import priv.droptea.emotion.processor.AudioProcessor;

/**
 * Sample rate transposer. Changes sample rate by using  interpolation 
 * 
 * Together with the time stretcher this can be used for pitch shifting.
 * @author Joren Six
 */
public class RateTransposer implements AudioProcessor {

	private double factor;
    private Resampler r;
    
	/**
	 * Create a new sample rate transposer. The factor determines the new sample
	 * rate. E.g. 0.5 is half the sample rate, 1.0 does not change a thing and
	 * 2.0 doubles the samplerate. If the samples are played at the original
	 * speed the pitch doubles (0.5), does not change (1.0) or halves (2)
	 * respectively. Playback length follows the same rules, obviously.
	 * 
	 * @param factor
	 *            Determines the new sample rate. E.g. 0.5 is half the sample
	 *            rate, 1.0 does not change a thing and 2.0 doubles the sample
	 *            rate. If the samples are played at the original speed the
	 *            pitch doubles (0.5), does not change (1.0) or halves (2)
	 *            respectively. Playback length follows the same rules,
	 *            obviously.
	 *            factory等于2的时候，采样翻倍，语速降低为原来一半
	 */
	public RateTransposer(double factor){
		this.factor = factor;
		r= new Resampler(false,0.1,4.0);
	}
	
	public void setFactor(double tempo){
		this.factor = tempo;
	}
	
	@Override
	public boolean process(AudioEvent audioEvent) {
		//处理前的音频数据
		float[] oldAudioDataBlock = audioEvent.getFloatBuffer();
		//处理后的音频数据保存在下面这个数组里
		float[] newAudioDataBlock = new float[(int) (oldAudioDataBlock.length * factor)];
		//factor大于1是上采样，增加时长，降低音调；小于1是下采样，减少时长，提高音调
		r.process(factor, oldAudioDataBlock, 0, oldAudioDataBlock.length
				, false, newAudioDataBlock, 0, newAudioDataBlock.length);
		//The size of the output buffer changes (according to factor). 
		audioEvent.setFloatBuffer(newAudioDataBlock);
		//Update overlap offset to match new buffer size
		audioEvent.setOverlap((int) (audioEvent.getOverlap() * factor));
		return true;
	}

	@Override
	public void processingFinished() {

	}

}
