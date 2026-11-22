//! Connection-scoped route choreography; independent of camera rotation.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RouteVisual {
 pub reveal:f32,
 pub opacity:f32,
 pub arrival:f32,
 pub flow:f32,
}
impl RouteVisual {
 pub fn hidden()->Self {Self{reveal:0.,opacity:0.,arrival:0.,flow:0.}}
 pub fn complete(motion:bool)->Self {Self{reveal:1.,opacity:1.,arrival:1.,flow:if motion{1.}else{0.}}}
}
pub struct RouteAnimation {
 key:String,
 state:String,
 started:f64,
 fade:Option<(f64,RouteVisual)>,
 last:RouteVisual,
}
fn ease(t:f32)->f32 {let t=t.clamp(0.,1.);t*t*(3.-2.*t)}
impl RouteAnimation {
 pub fn new()->Self {Self{key:String::new(),state:String::new(),started:0.,fade:None,last:RouteVisual::hidden()}}
 pub fn sample(&mut self,now:f64,key:&str,state:&str,motion:bool)->RouteVisual {
  // Handshakes can take seconds: start the reveal when the connection becomes
  // visible as established, rather than spending it behind "Connecting".
  let active=state=="connected";
  let was_active=self.state=="connected";
  if active && (!was_active || self.key!=key) {
   self.started=now;self.fade=None;self.key=key.to_owned();
  }
  if !active && was_active {self.fade=Some((now,self.last));}
  let visual=if !motion {
   self.started=now-2.;self.fade=None;
   if active {RouteVisual::complete(false)}else{RouteVisual::hidden()}
  }else if active {
   let age=(now-self.started).max(0.) as f32;
   RouteVisual{opacity:ease(age/0.32),reveal:ease(age/1.05),arrival:ease((age-0.96)/0.30),flow:ease((age-1.25)/0.30)}
  }else if let Some((start,mut visual))=self.fade {
   let fade=ease(((now-start)/0.32) as f32);
   visual.opacity*=1.-fade;
   if fade>=1. {self.fade=None;RouteVisual::hidden()}else{visual}
  }else{RouteVisual::hidden()};
  if self.state!=state {self.state=state.to_owned();}
  self.last=visual;visual
 }
 pub fn phase(&self,now:f64)->f32 {(((now-self.started-1.25).max(0.)*0.18).fract()) as f32}
}

#[cfg(test)]
mod tests {
 use super::*;
 #[test]
 fn connection_reveals_line_then_arrival_smoothly() {
  let mut a=RouteAnimation::new();let first=a.sample(0.,"RU:DE","connected",true);
  assert_eq!(first,RouteVisual::hidden());
  let mut previous=first;
  for i in 1..101 {
   let next=a.sample(i as f64*0.016,"RU:DE","connected",true);
   assert!(next.reveal>=previous.reveal && next.opacity>=previous.opacity && next.arrival>=previous.arrival);
   assert!(next.reveal-previous.reveal<0.04,"line jumped instead of animating");
   previous=next;
  }
  assert_eq!(previous,RouteVisual::complete(true));
 }
 #[test]
 fn connecting_to_connected_and_camera_frames_do_not_restart_intro() {
  let mut a=RouteAnimation::new();a.sample(10.,"RU:DE","connected",true);
  let before=a.sample(10.4,"RU:DE","connected",true);
  let connected=a.sample(10.5,"RU:DE","connected",true);
  let next_frame=a.sample(10.6,"RU:DE","connected",true);
  assert!(before.reveal>0. && before.reveal<1.);
  assert!(connected.reveal>before.reveal && next_frame.reveal>connected.reveal);
  assert!(a.phase(14.)>0.);
 }
 #[test]
 fn reconnecting_or_a_changed_route_get_a_fresh_intro() {
  let mut a=RouteAnimation::new();a.sample(0.,"RU:DE","connected",true);a.sample(2.,"RU:DE","connected",true);
  a.sample(3.,"RU:DE","disconnected",true);
  assert_eq!(a.sample(4.,"RU:DE","connected",true),RouteVisual::hidden());
  a.sample(5.,"RU:DE","connected",true);
  assert_eq!(a.sample(6.,"RU:NL","connected",true),RouteVisual::hidden());
 }
 #[test]
 fn disconnect_preserves_geometry_and_fades_it_out() {
  let mut a=RouteAnimation::new();a.sample(0.,"RU:DE","connected",true);a.sample(2.,"RU:DE","connected",true);
  assert_eq!(a.sample(3.,"RU:DE","disconnecting",true).opacity,1.);
  let fading=a.sample(3.16,"RU:DE","disconnected",true);
  assert!(fading.opacity>0. && fading.opacity<1.);assert_eq!(fading.reveal,1.);
  assert_eq!(a.sample(3.4,"RU:DE","disconnected",true).opacity,0.);
 }
 #[test]
 fn reduced_motion_is_immediate_and_never_rewinds_when_reenabled() {
  let mut a=RouteAnimation::new();
  assert_eq!(a.sample(0.,"RU:DE","connected",false),RouteVisual::complete(false));
  assert_eq!(a.sample(0.01,"RU:DE","connected",true).reveal,1.);
  assert_eq!(a.sample(1.,"RU:DE","disconnected",false),RouteVisual::hidden());
 }
 #[test]
 fn long_running_clock_keeps_subframe_precision() {
  let mut a=RouteAnimation::new();a.sample(1_000_000.,"RU:DE","connected",true);
  let first=a.sample(1_000_000.1,"RU:DE","connected",true);
  let next=a.sample(1_000_000.116,"RU:DE","connected",true);
  assert!(next.reveal>first.reveal && next.reveal-first.reveal<0.04);
 }
 #[test]
 fn a_long_handshake_cannot_consume_the_connected_intro() {
  let mut a=RouteAnimation::new();a.sample(0.,"RU:DE","connecting",true);
  assert_eq!(a.sample(5.,"RU:DE","connecting",true),RouteVisual::hidden());
  assert_eq!(a.sample(5.1,"RU:DE","connected",true),RouteVisual::hidden());
  let next=a.sample(5.3,"RU:DE","connected",true);
  assert!(next.reveal>0. && next.reveal<1.);
 }

}
