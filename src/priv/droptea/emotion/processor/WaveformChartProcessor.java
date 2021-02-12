package priv.droptea.emotion.processor;

import priv.droptea.emotion.AudioEvent;
import priv.droptea.emotion.panel.WaveformChartPanel;

public class WaveformChartProcessor  implements AudioProcessor {
	
	private WaveformChartPanel mWaveformChartPanel;
	
	public WaveformChartProcessor(WaveformChartPanel mWaveformChartPanel) {
		this.mWaveformChartPanel = mWaveformChartPanel;
	}

	@Override
	public boolean process(AudioEvent audioEvent) {
		float[] audioFloatBuffer = audioEvent.getFloatBuffer();
		float[] copyBuffer = new float[audioFloatBuffer.length];
		System.arraycopy(audioFloatBuffer,0, copyBuffer,0 ,audioFloatBuffer.length);
		AudioEvent copyAudioEvent = new AudioEvent(audioEvent.getFormat());
		copyAudioEvent.setDataForAnalysisInWaveformChart(audioEvent.getDataForAnalysisInWaveformChart());
		copyAudioEvent.getDataForAnalysisInWaveformChart().setFloatBufferCur(copyBuffer);
		mWaveformChartPanel.addAudioEvent(copyAudioEvent);
		return true;
	}

	@Override
	public void processingFinished() {
		// TODO Auto-generated method stub
		
	}	

}
