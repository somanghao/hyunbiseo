#!/bin/bash
# 현비서 시나리오 영상 만들기 — scenes.tsv(장면 id · 초 · 장 · 대사) + docs/img 아이디어 시트 + 에뮬 화면 한 장
# 사용: bash docs/demo/make-video.sh <에뮬캡처.png> <출력폴더>
# 결과: hyun-scenario.mp4(자막 트랙 포함) · hyun-scenario.srt · hyun-scenario.vtt
# 이 맥의 ffmpeg 엔 글자 그리기(drawtext·libass)가 없어 대사는 자막 트랙/파일로 낸다(PPT: 비디오 → 캡션 삽입 → .vtt)
set -euo pipefail
cd "$(dirname "$0")/../.."
SHOT="$1"; OUT="$2"; W="${TMPDIR:-/tmp}/hyun-video"
rm -rf "$W"; mkdir -p "$W/tiles" "$W/frames" "$W/clips" "$OUT"

# 1) 시트 → 칸(960×454, 16px 간격)
cut1(){ ffmpeg -v error -y -i "docs/img/$1.png" -vf "crop=960:454:$((16+$3*976)):$((16+$2*470))" "$W/tiles/$4.png"; }
sheet(){ local s=$1 c=$2 n=0; shift 2; for id in "$@"; do cut1 "$s" $((n/c)) $((n%c)) "$id"; n=$((n+1)); done; }
sheet ideas-1-ignition 3 air fortune rival routine weekly charge
sheet ideas-2-ready 3 habit depart crew doors launch
sheet ideas-3-drive 3 combo brakecoach drowsy alert arrive10 dj
sheet ideas-4-stop 2 quiz score signal stretch
sheet ideas-5-park 3 spot highlight wait nearby wash
sheet ideas-6-off 2 stamp morning leftbehind progress
sheet ideas-7-moments 3 first long night rain birthday hundred star1000
sheet ideas2-1-family 2 kidquiz date parent childlock
sheet ideas2-2-season 3 holiday firstsnow blossom heat suneung
sheet ideas2-3-character 2 level mood anniv memory
sheet ideas2-4-care 2 cost service tireseason ubi
sheet ideas2-5-share-commute 3 sharecard league commute parkhunt schoolzone
sheet ideas2-6-play-gleo 3 voicegame senior gleo3

# 2) 장면 = 에뮬 위 바(76) + 칸 2배(1920×908) + 아래 바(96) → 실제 차 화면처럼
ffmpeg -v error -y -i "$SHOT" -vf "scale=1920:1080" "$W/frames/@intro.png"
cp "$W/frames/@intro.png" "$W/frames/@outro.png"
ffmpeg -v error -y -i "$SHOT" -vf "scale=1920:1080,crop=1920:76:0:0" "$W/top.png"
ffmpeg -v error -y -i "$SHOT" -vf "scale=1920:1080,crop=1920:96:0:984" "$W/bot.png"

: > "$W/list.txt"; : > "$OUT/hyun-scenario.srt"
t=0; n=0; idx=0
ts(){ awk -v s="$1" -v c="$2" 'BEGIN{h=int(s/3600); m=int((s%3600)/60); x=s-h*3600-m*60; printf "%02d:%02d:%02d%s%03d", h, m, int(x), c, int((x-int(x))*1000+0.5)}'; }
while IFS=$'\t' read -r id dur ch lines; do
  [ -z "$id" ] && continue; [ "$ch" = "-" ] && ch=""
  n=$((n+1)); f="$W/frames/$id.png"
  if [ ! -f "$f" ]; then
    ffmpeg -v error -y -i "$W/top.png" -i "$W/tiles/$id.png" -i "$W/bot.png" \
      -filter_complex "[1:v]scale=1920:908:flags=lanczos[m];[0:v][m][2:v]vstack=inputs=3" "$f" </dev/null
  fi
  c="$W/clips/$(printf %03d $n).mp4"
  ffmpeg -v error -y -loop 1 -t "$dur" -i "$f" -f lavfi -t "$dur" -i anullsrc=r=48000:cl=stereo \
    -vf "fps=30,format=yuv420p,fade=t=in:st=0:d=0.25,fade=t=out:st=$(awk -v d=$dur 'BEGIN{print d-0.25}'):d=0.25" \
    -c:v libx264 -preset medium -crf 18 -c:a aac -shortest "$c" </dev/null
  echo "file '$c'" >> "$W/list.txt"
  # 자막: 대사를 장면 시간에 고르게 나눔 · 첫 줄 위에 장 이름
  IFS=$'\n' read -r -d '' -a L < <(awk -v s="$lines" 'BEGIN{n=split(s,a," // "); for(i=1;i<=n;i++) print a[i]}' && printf '\0') || true
  k=${#L[@]}
  for ((j=0; j<k; j++)); do
    s=$(awk -v t=$t -v d=$dur -v j=$j -v k=$k 'BEGIN{print t+0.2+(d-0.4)*j/k}')
    e=$(awk -v t=$t -v d=$dur -v j=$j -v k=$k 'BEGIN{print t+0.2+(d-0.4)*(j+1)/k}')
    idx=$((idx+1)); txt="${L[$j]}"; txt="${txt/#(설명) /}"
    { echo "$idx"; echo "$(ts $s ,) --> $(ts $e ,)"; [ $j -eq 0 ] && [ -n "$ch" ] && echo "【$ch】"; echo "$txt"; echo; } >> "$OUT/hyun-scenario.srt"
  done
  t=$(awk -v t=$t -v d=$dur 'BEGIN{print t+d}')
done < <(awk -F"\t" 'BEGIN{OFS="\t"} {if($3=="")$3="-"; print}' docs/demo/scenes.tsv)   # 빈 장 칸을 "-" 로(read 가 연속 탭을 합침)

ffmpeg -v error -y -f concat -safe 0 -i "$W/list.txt" -c copy "$W/body.mp4"
ffmpeg -v error -y -i "$W/body.mp4" -i "$OUT/hyun-scenario.srt" -map 0:v -map 0:a -map 1 -c:v copy -c:a copy -c:s mov_text \
  -metadata:s:s:0 language=kor -disposition:s:0 default -movflags +faststart "$OUT/hyun-scenario.mp4"
ffmpeg -v error -y -i "$OUT/hyun-scenario.srt" "$OUT/hyun-scenario.vtt"
echo "장면 $n · 길이 ${t}초 → $OUT"
