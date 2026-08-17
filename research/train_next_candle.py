#!/usr/bin/env python3
from __future__ import annotations
import argparse,json
from pathlib import Path
import numpy as np,pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score,brier_score_loss,log_loss
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

def make_features(df):
 df=df.sort_values("timestamp").reset_index(drop=True);o=df.open.to_numpy(float);h=df.high.to_numpy(float);l=df.low.to_numpy(float);c=df.close.to_numpy(float);X=[];y=[]
 for i in range(15,len(df)-1):
  rr=np.maximum(1e-9,h[i-14:i+1]-l[i-14:i+1]);avg=float(np.mean(rr));body=c[i]-o[i];upper=max(0,h[i]-max(o[i],c[i]));lower=max(0,min(o[i],c[i])-l[i]);X.append([body/avg,upper/avg,lower/avg,(c[i]-c[i-3])/avg,(c[i]-c[i-7])/avg,(np.mean(c[i-2:i+1])-np.mean(c[i-7:i+1]))/avg,np.polyfit(np.arange(8),c[i-7:i+1],1)[0]/avg,(h[i]-l[i])/avg-1]);y.append(int(c[i+1]>o[i+1]))
 return np.asarray(X),np.asarray(y)

def main():
 ap=argparse.ArgumentParser();ap.add_argument("csv");ap.add_argument("--out",default="models");a=ap.parse_args();df=pd.read_csv(a.csv);req={"timestamp","open","high","low","close"};miss=req-set(df.columns)
 if miss: raise SystemExit("Missing columns: "+str(sorted(miss)))
 X,y=make_features(df);s=int(len(X)*.8);m=Pipeline([("scale",StandardScaler()),("model",LogisticRegression(max_iter=2000,class_weight="balanced"))]);m.fit(X[:s],y[:s]);p=m.predict_proba(X[s:])[:,1];r={"train_rows":s,"test_rows":len(y)-s,"accuracy":float(accuracy_score(y[s:],p>=.5)),"log_loss":float(log_loss(y[s:],p)),"brier_score":float(brier_score_loss(y[s:],p)),"approved_for_live":False};o=Path(a.out);o.mkdir(parents=True,exist_ok=True);(o/"validation_report.json").write_text(json.dumps(r,indent=2));print(json.dumps(r,indent=2))
if __name__=="__main__":main()
