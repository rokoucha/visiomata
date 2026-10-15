# mbaff.mp4

ハードウェアH.264デコーダーがMBAFFのインターピクチャをデコードできるかを実行時に確かめるための、
352x288 / 7サンプルのクリップ。`AvcMbaffProbe` が読み込む。

放送のインターレース素材をvendoredのmpeg2toh264へ通したものと同じ構成
(High profile / MBAFF / long-term reference) にするため、同じ経路で生成している。

```
ffmpeg -f lavfi -i testsrc2=size=352x288:rate=60000/1001 -t 0.2 \
  -vf "interlace=scan=tff:lowpass=0" \
  -c:v mpeg2video -profile:v 0 -level 2 -flags +ilme+ildct -b:v 1M -g 15 \
  probe_src.m2v

cargo build --release -p mpeg2toh264-cli   # src/main/rust/vendor/mpeg2toh264
mpeg2toh264 -q probe_src.m2v mbaff.mp4
```

MBAFFであることは次で確認できる (`frame_mbs_only_flag=0`, `mb_adaptive_frame_field_flag=1`)。

```
ffmpeg -i mbaff.mp4 -c copy -bsf:v trace_headers -f null -
```
