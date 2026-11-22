//! Opt-in native presentation probe; never creates or modifies a VPN session.
use crate::{MotionFixtureWindow,Theme,route_animation::RouteAnimation,chart_animation::ChartAnimation,traffic};
use slint::ComponentHandle;
pub fn run(folder:std::path::PathBuf)->anyhow::Result<()> {
 std::fs::create_dir_all(&folder)?;
 let ui=MotionFixtureWindow::new()?;
 ui.global::<crate::MetricFormatting>().on_character(crate::metric_character);
 ui.global::<crate::MetricFormatting>().on_count(|v|v.chars().count() as i32);
 let motion=std::env::var_os("KAWA_COMPONENT_REDUCED").is_none();
 ui.global::<Theme>().set_motion(motion);ui.set_time("00:00:00".into());
 ui.show()?;
 let weak=ui.as_weak();let start=std::time::Instant::now();let mut animation=RouteAnimation::new();
 let mut chart=ChartAnimation::new();let mut sample=-1;let mut frames=Vec::new();
 let timer=slint::Timer::default();
 timer.start(slint::TimerMode::Repeated,std::time::Duration::from_millis(33),move||{
  let Some(ui)=weak.upgrade()else{return;};let now=start.elapsed().as_secs_f64();
  let state=if now<1.5 {"connecting"}else if now<4. {"connected"}else{"disconnected"};
  let route=animation.sample(now,"RU:DE",state,motion);
  ui.set_reveal(route.reveal);ui.set_alpha(route.opacity);ui.set_arrival(route.arrival);ui.set_phase(animation.phase(now));
  let second=if state=="connected" {(now-1.5) as u64}else{0};ui.set_time(format!("00:00:{second:02}").into());
  let step=now as i32;
  if step!=sample {let mut values=[28.;32];if step>=2 {let index=28usize.saturating_sub((step-2) as usize);values[index]=4.;values[index+1]=17.;}chart.update(values,now,motion);sample=step;}
  ui.set_graph(traffic::sparkline_points(&chart.values(now)));
  let index=frames.len();crate::snapshot(ui.window(),&folder.join(format!("frame-{index:03}.png")));
  frames.push(serde_json::json!({"frame":index,"seconds":now,"state":state,"reveal":route.reveal,"alpha":route.opacity,"arrival":route.arrival}));
  if now>=4.5 {let _=std::fs::write(folder.join("frames.json"),serde_json::to_vec_pretty(&frames).unwrap());let _=slint::quit_event_loop();}
 });
 slint::run_event_loop()?;Ok(())
}
