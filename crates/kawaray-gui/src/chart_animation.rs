//! Interpolates only the presentation of measured samples, never byte counters.
pub struct ChartAnimation {from:[f32;32],target:[f32;32],started:f64}
impl ChartAnimation {
 pub fn new()->Self {Self{from:[28.;32],target:[28.;32],started:0.}}
 pub fn update(&mut self,target:[f32;32],now:f64,motion:bool) {self.from=if motion{self.values(now)}else{target};self.target=target;self.started=now;}
 pub fn values(&self,now:f64)->[f32;32] {let t=(((now-self.started)/0.8) as f32).clamp(0.,1.);let t=t*t*(3.-2.*t);std::array::from_fn(|i|self.from[i]+(self.target[i]-self.from[i])*t)}
 pub fn animating(&self,now:f64)->bool {self.from!=self.target && now-self.started<0.8}
 pub fn finish(&mut self) {self.from=self.target;}
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn graph_reaches_actual_sample_without_jumping() {
  let mut a=ChartAnimation::new();let mut sample=[28.;32];sample[25]=4.;
  a.update(sample,5.,true);assert_eq!(a.values(5.),[28.;32]);
  let half=a.values(5.4)[25];assert!(half>4. && half<28.);
  assert_eq!(a.values(5.9),sample);assert!(!a.animating(5.9));
 }
 #[test] fn a_new_sample_starts_from_the_current_curve() {
  let mut a=ChartAnimation::new();a.update([4.;32],0.,true);
  let current=a.values(0.3);a.update([12.;32],0.3,true);
  assert_eq!(a.values(0.3),current);assert_eq!(a.values(1.2),[12.;32]);
 }
 #[test] fn disabling_motion_keeps_the_actual_final_curve() {
  let mut a=ChartAnimation::new();a.update([4.;32],0.,true);a.finish();
  assert_eq!(a.values(0.1),[4.;32]);assert!(!a.animating(0.1));
  a.update([20.;32],0.2,false);assert_eq!(a.values(0.2),[20.;32]);
 }
 #[test] fn idle_chart_has_no_animation_work() {
  let mut a=ChartAnimation::new();a.update([28.;32],3.,true);
  assert!(!a.animating(3.1));assert_eq!(a.values(3.1),[28.;32]);
 }
}
