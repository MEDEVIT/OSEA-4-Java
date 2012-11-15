/*
 * Copyright (c) 2012 Patrick S. Hamilton (pat@eplimited.com), Wolfgang Halbeisen (halbeisen.wolfgang@gmail.com)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without restriction, 
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, 
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, 
 * subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies 
 * or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, 
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE 
 * AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package eplimited.osea.classification;

import java.util.Arrays;

/**
 * This file contains functions for determining the QRS onset, QRS offset,
 * beat onset, beat offset, polarity, and isoelectric level for a beat.
 */
public class BeatAnalyzer 
	{

	private BDACParameters bdacParas;
	
	public int ISO_LENGTH1 ;
	public int ISO_LENGTH2 ;
	public int ISO_LIMIT	= 20 ;
	
	public int INF_CHK_N ;
	
	/**
	 * Create a new beatAnalyzer with the given parameters.
	 * @param bdacParameters The sampleRate-dependent parameters
	 */
	public BeatAnalyzer(BDACParameters bdacParameters) 
		{
		bdacParas = bdacParameters ;
		ISO_LENGTH1 = bdacParameters.BEAT_MS50 ;
		ISO_LENGTH2 = bdacParameters.BEAT_MS80 ;
		INF_CHK_N   = bdacParameters.BEAT_MS40 ;
		}
	
	/**
	 * IsoCheck determines whether the amplitudes of a run
	 * of data fall within a sufficiently small amplitude that
	 * the run can be considered isoelectric.
	 * 
	 * @param data
	 * @param isoLength
	 * @return
	 */
	public boolean IsoCheck(int[] data, int isoLength)
		{
		int i, max, min ;
	
		for(i = 1, max=min = data[0]; i < isoLength && i < data.length; ++i)
			{
			if(data[i] > max)
				max = data[i] ;
			else if(data[i] < min)
				min = data[i] ;
			}
	
		return(max - min < ISO_LIMIT);
		}
	
	/**
	 * AnalyzeBeat takes a beat buffer as input and returns (via pointers)
	 * estimates of the QRS onset, QRS offset, polarity, isoelectric level
	 * beat beginning (P-wave onset), and beat ending (T-wave offset).
	 * Analyze Beat assumes that the beat has been sampled at 100 Hz, is
	 * BEATLGTH long, and has an R-wave location of roughly FIDMARK.
	 * 
	 * Note that beatBegin is the number of samples before FIDMARK that
	 * the beat begins and beatEnd is the number of samples after the
	 * FIDMARK that the beat ends.
	 * 
	 * @param beat A beat buffer
	 * @return The result of the analysis
	 */
	public AnalyzeBeatResult AnalyzeBeat(int[] beat)
		{
		AnalyzeBeatResult result = new AnalyzeBeatResult();
		int maxSlope = 0, maxSlopeI, minSlope = 0, minSlopeI  ;
		int maxV, minV ;
		int isoStart, isoEnd ;
		int slope, i ;
	
		// Search back from the fiducial mark to find the isoelectric
		// region preceeding the QRS complex.

		for(i = bdacParas.FIDMARK-ISO_LENGTH2; (i > 0) && (IsoCheck(Arrays.copyOfRange(beat, i, bdacParas.BEATLGTH),ISO_LENGTH2) == false); --i) ;
	
		// If the first search didn't turn up any isoelectric region, look for
		// a shorter isoelectric region.
	
		if(i == 0)
			{
			for(i = bdacParas.FIDMARK-ISO_LENGTH1; (i > 0) && (IsoCheck(Arrays.copyOfRange(beat, i, bdacParas.BEATLGTH),ISO_LENGTH1) == false); --i) ;
			isoStart = i + (ISO_LENGTH1 - 1) ;
			}
		else isoStart = i + (ISO_LENGTH2 - 1) ;
	
		// Search forward from the R-wave to find an isoelectric region following
		// the QRS complex.
	
		for(i = bdacParas.FIDMARK; (i < bdacParas.BEATLGTH) && (IsoCheck(Arrays.copyOfRange(beat, i, bdacParas.BEATLGTH),ISO_LENGTH1) == false); ++i) ;
		isoEnd = i ;
	
		// Find the maximum and minimum slopes on the
		// QRS complex.
	
		i = bdacParas.FIDMARK-bdacParas.BEAT_MS150 ;
		maxSlope  = beat[i] - beat[i-1] ;
		maxSlopeI = minSlopeI = i ;
	
		for(; i < bdacParas.FIDMARK+bdacParas.BEAT_MS150; ++i)
			{
			slope = beat[i] - beat[i-1] ;
			if(slope > maxSlope)
				{
				maxSlope = slope ;
				maxSlopeI = i ;
				}
			else if(slope < minSlope)
				{
				minSlope = slope ;
				minSlopeI = i ;
				}
			}
	
		// Use the smallest of max or min slope for search parameters.
	
		if(maxSlope > -minSlope)
			maxSlope = -minSlope ;
		else minSlope = -maxSlope ;
	
		if(maxSlopeI < minSlopeI)
			{
	
			// Search back from the maximum slope point for the QRS onset.
	
			for(i = maxSlopeI;
				(i > 0) && ((beat[i]-beat[i-1]) > (maxSlope >> 2)); --i) ;
				result.onset = i-1 ;
	
			// Check to see if this was just a brief inflection.
	
			for(; (i > 0) && (i > result.onset-INF_CHK_N) && ((beat[i]-beat[i-1]) <= (maxSlope >>2)); --i) ;
			if(i > result.onset-INF_CHK_N)
				{
				for(;(i > 0) && ((beat[i]-beat[i-1]) > (maxSlope >> 2)); --i) ;
				result.onset = i-1 ;
				}
			i = result.onset+1 ;
	
			// Check to see if a large negative slope follows an inflection.
			// If so, extend the onset a little more.
	
			for(;(i > 0) && (i > result.onset-INF_CHK_N) && ((beat[i-1]-beat[i]) < (maxSlope>>2)); --i);
			if(i > result.onset-INF_CHK_N)
				{
				for(; (i > 0) && ((beat[i-1]-beat[i]) > (maxSlope>>2)); --i) ;
				result.onset = i-1 ;
				}
	
			// Search forward from minimum slope point for QRS offset.
	
			for(i = minSlopeI;
				(i < bdacParas.BEATLGTH) && ((beat[i] - beat[i-1]) < (minSlope >>2)); ++i);
			result.offset = i ;
	
			// Make sure this wasn't just an inflection.
	
			for(; (i < bdacParas.BEATLGTH) && (i < result.offset+INF_CHK_N) && ((beat[i]-beat[i-1]) >= (minSlope>>2)); ++i) ;
			if(i < result.offset+INF_CHK_N)
				{
				for(;(i < bdacParas.BEATLGTH) && ((beat[i]-beat[i-1]) < (minSlope >>2)); ++i) ;
				result.offset = i ;
				}
			i = result.offset ;
	
			// Check to see if there is a significant upslope following
			// the end of the down slope.
	
			for(;((i < bdacParas.BEATLGTH) && i < result.offset+bdacParas.BEAT_MS40) && ((beat[i-1]-beat[i]) > (minSlope>>2)); ++i);
			if(i < result.offset+bdacParas.BEAT_MS40)
				{
				for(; (i < bdacParas.BEATLGTH) && ((beat[i-1]-beat[i]) < (minSlope>>2)); ++i) ;
				result.offset = i ;
	
				// One more search motivated by PVC shape in 123.
	
				for(; (i < bdacParas.BEATLGTH) && (i < result.offset+bdacParas.BEAT_MS60) && (beat[i]-beat[i-1] > (minSlope>>2)); ++i) ;
				if(i < result.offset + bdacParas.BEAT_MS60)
					{
					for(;(i < bdacParas.BEATLGTH) && (i <bdacParas.BEATLGTH) && (beat[i]-beat[i-1] < (minSlope>>2)); ++i) ;
					result.offset = i ;
					}
				}
			}
	
		else
			{
	
			// Search back from the minimum slope point for the QRS onset.
	
			for(i = minSlopeI;
				(i > 0) && ((beat[i]-beat[i-1]) < (minSlope >> 2)); --i) ;
			result.onset = i-1 ;
	
			// Check to see if this was just a brief inflection.
	
			for(; (i > 0) && (i > result.onset-INF_CHK_N) && ((beat[i]-beat[i-1]) >= (minSlope>>2)); --i) ;
			if(i > result.onset-INF_CHK_N)
				{
				for(; (i > 0) && ((beat[i]-beat[i-1]) < (minSlope>>2));--i) ;
				result.onset = i-1 ;
				}
			i = result.onset+1 ;
	
			// Check for significant positive slope after a turning point.
	
			for(;(i > 0) && (i > result.onset-INF_CHK_N) && ((beat[i-1]-beat[i]) > (minSlope>>2)); --i);
			if(i > result.onset-INF_CHK_N)
				{
				for(;(i > 0) && ((beat[i-1]-beat[i]) < (minSlope>>2)); --i) ;
				result.onset = i-1 ;
				}
	
			// Search forward from maximum slope point for QRS offset.
	
			for(i = maxSlopeI;
				(i < bdacParas.BEATLGTH) && ((beat[i] - beat[i-1]) > (maxSlope >>2)); ++i) ;
			result.offset = i ;
	
			// Check to see if this was just a brief inflection.
	
			for(; (i < bdacParas.BEATLGTH) && (i < result.offset+INF_CHK_N) && ((beat[i] - beat[i-1]) <= (maxSlope >> 2)); ++i) ;
			if(i < result.offset+INF_CHK_N)
				{
				for(;(i < bdacParas.BEATLGTH) && ((beat[i] - beat[i-1]) > (maxSlope >>2)); ++i) ;
				result.offset = i ;
				}
			i = result.offset ;
	
			// Check to see if there is a significant downslope following
			// the end of the up slope.
	
			for(;(i < bdacParas.BEATLGTH) && (i < result.offset+bdacParas.BEAT_MS40) && ((beat[i-1]-beat[i]) < (maxSlope>>2)); ++i);
			if(i < result.offset+bdacParas.BEAT_MS40)
				{
				for(; (i < bdacParas.BEATLGTH) && ((beat[i-1]-beat[i]) > (maxSlope>>2)); ++i) ;
				result.offset = i ;
				}
			}
	
		// If the estimate of the beginning of the isoelectric level was
		// at the beginning of the beat, use the slope based QRS onset point
		// as the iso electric point.
	
		if((isoStart == ISO_LENGTH1-1)&& (result.onset > isoStart)) // ** 4/19 **
			isoStart = result.onset ;
	
		// Otherwise, if the isoelectric start and the slope based points
		// are close, use the isoelectric start point.
	
		else if(result.onset-isoStart < bdacParas.BEAT_MS50)
			result.onset = isoStart ;
	
		// If the isoelectric end and the slope based QRS offset are close
		// use the isoelectic based point.
	
		if(isoEnd - result.offset < bdacParas.BEAT_MS50)
			result.offset = isoEnd ;
	
		result.isoLevel = beat[isoStart] ;
	
	
		// Find the maximum and minimum values in the QRS.
	
		for(i = result.onset, maxV = minV = beat[result.onset]; i < result.offset; ++i)
			if(beat[i] > maxV)
				maxV = beat[i] ;
			else if(beat[i] < minV)
				minV = beat[i] ;
	
		// If the offset is significantly below the onset and the offset is
		// on a negative slope, add the next up slope to the width.
	
		if((beat[result.onset]-beat[result.offset] > ((maxV-minV)>>2)+((maxV-minV)>>3)))
			{
	
			// Find the maximum slope between the finish and the end of the buffer.
	
			for(i = maxSlopeI = result.offset, maxSlope = beat[result.offset] - beat[result.offset-1];
				(i < result.offset+bdacParas.BEAT_MS100) && (i < bdacParas.BEATLGTH); ++i)
				{
				slope = beat[i]-beat[i-1] ;
				if(slope > maxSlope)
					{
					maxSlope = slope ;
					maxSlopeI = i ;
					}
				}
	
			// Find the new offset.
	
			if(maxSlope > 0)
				{
				for(i = maxSlopeI;
					(i < bdacParas.BEATLGTH) && (beat[i]-beat[i-1] > (maxSlope>>1)); ++i) ;
				result.offset = i ;
				}
			}
	
		// Determine beginning and ending of the beat.
		// Search for an isoelectric region that precedes the R-wave.
		// by at least 250 ms.
	
		for(i = bdacParas.FIDMARK-bdacParas.BEAT_MS250;
			(i > bdacParas.BEAT_MS80) && (IsoCheck(Arrays.copyOfRange(beat, i-bdacParas.BEAT_MS80, bdacParas.BEATLGTH),bdacParas.BEAT_MS80) == false); --i) ;
		result.beatBegin = i ;
	
		// If there was an isoelectric section at 250 ms before the
		// R-wave, search forward for the isoelectric region closest
		// to the R-wave.  But leave at least 50 ms between beat begin
		// and onset, or else normal beat onset is within PVC QRS complexes.
		// that screws up noise estimation.
	
		if(result.beatBegin == bdacParas.FIDMARK-bdacParas.BEAT_MS250)
			{
			for(; (i < result.onset-bdacParas.BEAT_MS50) &&
				(IsoCheck(Arrays.copyOfRange(beat, i-bdacParas.BEAT_MS80, bdacParas.BEATLGTH),bdacParas.BEAT_MS80)); ++i) ;
			result.beatBegin = i-1 ;
			}
	
		// Rev 1.1
		else if(result.beatBegin == bdacParas.BEAT_MS80)
			{
			for(; (i < result.onset) && (IsoCheck(Arrays.copyOfRange(beat, i-bdacParas.BEAT_MS80, bdacParas.BEATLGTH),bdacParas.BEAT_MS80) == false); ++i);
			if(i < result.onset)
				{
				for(; (i < result.onset) && (IsoCheck(Arrays.copyOfRange(beat, i-bdacParas.BEAT_MS80, bdacParas.BEATLGTH),bdacParas.BEAT_MS80)); ++i) ;
				if(i < result.onset)
					result.beatBegin = i-1 ;
				}
			}
	
		// Search for the end of the beat as the first isoelectric
		// segment that follows the beat by at least 300 ms.
	
		for(i = bdacParas.FIDMARK+bdacParas.BEAT_MS300;
			(i < bdacParas.BEATLGTH) && (IsoCheck(Arrays.copyOfRange(beat, i, bdacParas.BEATLGTH),bdacParas.BEAT_MS80) == false); ++i) ;
		result.beatEnd = i ;
	
		// Calculate beat amplitude.
	
		maxV=minV=beat[result.onset] ;
		for(i = result.onset; i < result.offset; ++i)
			if(beat[i] > maxV)
				maxV = beat[i] ;
			else if(beat[i] < minV)
				minV = beat[i] ;
		result.amp = maxV-minV ;
	
		return result;
		}
	
	/**
	 * The result of a beat analysis
	 */
	public class AnalyzeBeatResult 
		{
		public int onset ;
		public int offset ;
		public int isoLevel ;
		public int beatBegin ;
		public int beatEnd ;
		public int amp ;
		}
	}
