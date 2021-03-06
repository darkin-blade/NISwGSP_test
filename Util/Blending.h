#if !defined(Blending_H)
#define Blending_H

#include "../common.h"

Mat getMatOfLinearBlendWeight(const Mat & image);

vector<Mat> getMatsLinearBlendWeight(const vector<Mat> & images);

Mat Blending(const vector<Mat> & images,
             const vector<Point2f> & origins,
             const Size2f target_size,
             const vector<Mat> & weight_mask,
             const bool ignore_weight_mask = true);

#endif