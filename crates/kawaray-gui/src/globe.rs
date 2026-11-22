//! Screen-space arcball control. The pointer sets a target orientation and the
//! globe eases toward it (critically damped), so uneven mouse events never show
//! up as jerks; once it arrives it stays put (no idle spin or release drift).
//! A frame costs well under a millisecond, so it is drawn on the UI thread in
//! step with the timer: no cross-thread hand-off that could drop frames.
use std::{cell::RefCell, rc::Rc, time::{Duration, Instant}};
use slint::{ComponentHandle, Image, SharedPixelBuffer, Rgba8Pixel};
use crate::{AppWindow, Theme, route_animation::{RouteAnimation,RouteVisual}};
// Rendered at 2x the reference geometry (480 px globe) so large and HiDPI
// windows show crisp dots instead of an upscaled bitmap.
const SIZE: usize = 960;
const K: f32 = SIZE as f32 / 480.;
const C: f32 = SIZE as f32 / 2.;
const R: f32 = 212. * K;
type V = [f32; 3];
type Q = [f32; 4]; // w, x, y, z
fn unit(lat:f32, lon:f32)->V { let(a,b)=(lat.to_radians(),lon.to_radians());[a.cos()*b.sin(),a.sin(),a.cos()*b.cos()] }
fn dot3(a:V,b:V)->f32 { a[0]*b[0]+a[1]*b[1]+a[2]*b[2] }
fn normalized(q:Q)->Q {let n=q.iter().map(|x|x*x).sum::<f32>().sqrt().max(0.00001);q.map(|x|x/n)}
fn mul(a:Q,b:Q)->Q {normalized([a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0]])}
fn home()->Q {mul([(0.35f32/2.).cos(),(0.35f32/2.).sin(),0.,0.],[(-0.3f32/2.).cos(),0.,(-0.3f32/2.).sin(),0.])}
fn ball(x:f32,y:f32)->V {let(x,y)=((x-0.5)/0.441667,(0.5-y)/0.441667);let d=x*x+y*y;if d>1. {let r=d.sqrt();[x/r,y/r,0.]}else{[x,y,(1.-d).sqrt()]}}
fn between(a:V,b:V)->Q {
 let w=1.+dot3(a,b);
 if w<0.00001 {let axis=if a[0].abs()<0.8 {[0.,-a[2],a[1]]}else{[-a[1],a[0],0.]};return normalized([0.,axis[0],axis[1],axis[2]]);}
 normalized([w,a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]])
}
fn matrix(q:Q)->[[f32;3];3] {let[w,x,y,z]=q;[[1.-2.*(y*y+z*z),2.*(x*y-z*w),2.*(x*z+y*w)],[2.*(x*y+z*w),1.-2.*(x*x+z*z),2.*(y*z-x*w)],[2.*(x*z-y*w),2.*(y*z+x*w),1.-2.*(x*x+y*y)]]}
fn project(v:V,m:&[[f32;3];3])->V {[dot3(v,m[0]),dot3(v,m[1]),dot3(v,m[2])]}
fn arc(a:V,b:V,t:f32)->V {let angle=dot3(a,b).clamp(-1.,1.).acos();if angle.sin().abs()<0.001{return a}let x=((1.-t)*angle).sin()/angle.sin();let y=(t*angle).sin()/angle.sin();[a[0]*x+b[0]*y,a[1]*x+b[1]*y,a[2]*x+b[2]*y]}
/// Shortest-path normalized blend between two orientations.
fn nlerp(a:Q,b:Q,t:f32)->Q {let b=if a.iter().zip(b).map(|(x,y)|x*y).sum::<f32>()<0. {b.map(|x|-x)}else{b};normalized(std::array::from_fn(|i|a[i]*(1.-t)+b[i]*t))}
fn angle_between(a:Q,b:Q)->f32 {2.*a.iter().zip(b).map(|(x,y)|x*y).sum::<f32>().abs().min(1.).acos()}
/// Time constant of the pointer smoothing, seconds.
const FOLLOW:f32=0.045;
struct Camera {rotation:Q, target:Q, last:Option<V>, reset:Option<(Q,Instant)>, dirty:bool}
impl Camera {
 fn new()->Self {Self{rotation:home(),target:home(),last:None,reset:None,dirty:true}}
 fn drag(&mut self,x:f32,y:f32,down:bool) {
  if !down {self.last=None;return;}
  self.reset=None;let current=ball(x,y);
  if let Some(previous)=self.last {self.target=mul(between(previous,current),self.target);self.dirty=true;}
  self.last=Some(current);
 }
 fn go_home(&mut self) {self.last=None;self.reset=Some((self.rotation,Instant::now()));self.dirty=true;}
 /// Advances by `dt` seconds; true when the picture changed.
 fn tick(&mut self,motion:bool,dt:f32)->bool {
  if let Some((from,start))=self.reset {
   let t=if motion {(start.elapsed().as_secs_f32()/0.45).min(1.)}else{1.};let e=1.-(1.-t).powi(3);
   self.rotation=nlerp(from,home(),e);self.target=self.rotation;self.dirty=true;if t>=1. {self.reset=None;}
  } else if self.rotation!=self.target {
   if !motion || angle_between(self.rotation,self.target)<0.0004 {self.rotation=self.target;}
   else {self.rotation=nlerp(self.rotation,self.target,1.-(-dt.max(0.)/FOLLOW).exp());}
   self.dirty=true;
  }
  self.dirty
 }
}
#[derive(Clone,Copy)]
struct Frame {rotation:Q,from:Option<V>,to:Option<V>,route:RouteVisual,phase:f32}
/// Colors of one theme: the dark-theme globe is a dark sphere with light dots,
/// the light-theme one a pale sphere with graphite dots.
#[derive(Clone,Copy)]
struct Ink {sphere:(f32,f32,f32),limb:[u8;3],dot:(f32,f32),route:[u8;3],head:([u8;3],[u8;3]),origin:[u8;3],idle:[u8;3]}
const DARK:Ink=Ink{sphere:(10.,18.,5.),limb:[105,105,105],dot:(80.,115.),route:[74,207,168],head:([215,249,236],[238,255,249]),origin:[222,222,222],idle:[145,145,145]};
const LIGHT:Ink=Ink{sphere:(222.,22.,8.),limb:[176,184,194],dot:(150.,-95.),route:[22,150,110],head:([120,210,180],[16,120,88]),origin:[58,66,78],idle:[120,128,138]};
struct Renderer {points:Vec<V>,base:Vec<u8>,ink:Ink}
impl Renderer {
 fn new()->Self {Self::themed(false)}
 fn themed(light:bool)->Self {
  let ink=if light {LIGHT}else{DARK};
  let points=include_bytes!("../ui/assets/globe-points.bin").chunks_exact(8).map(|v|unit(f32::from_le_bytes(v[..4].try_into().unwrap()),f32::from_le_bytes(v[4..].try_into().unwrap()))).collect();
  let mut base=vec![0;SIZE*SIZE*4];for y in 0..SIZE {for x in 0..SIZE {let dx=(x as f32-C)/R;let dy=(y as f32-C)/R;let d=dx*dx+dy*dy;if d<=1. {let shade=(ink.sphere.0+ink.sphere.1*(1.-d).sqrt()+ink.sphere.2*(-dx-dy).max(0.)).clamp(0.,255.) as u8;let i=(y*SIZE+x)*4;base[i..i+4].copy_from_slice(&[shade,shade,shade,255]);}}}
  // The limb is static: don't rasterize its 480 samples on every frame.
  for i in 0..SIZE {let a=i as f32*std::f32::consts::TAU/SIZE as f32;dot(&mut base,C+R*a.cos(),C+R*a.sin(),0.55,ink.limb,0.45);}
  Self{points,base,ink}
 }
 #[cfg(test)] fn render(&self,frame:Frame)->Vec<u8> {let mut pixels=vec![0;SIZE*SIZE*4];self.render_into(&mut pixels,frame);pixels}
 fn render_into(&self,pixels:&mut [u8],frame:Frame) {
  pixels.copy_from_slice(&self.base);let pixels=&mut *pixels;let m=matrix(frame.rotation);
  for p in &self.points {let p=project(*p,&m);if p[2]>0. {let shade=(self.ink.dot.0+self.ink.dot.1*p[2]).clamp(0.,255.) as u8;dot(pixels,C+R*p[0],C-R*p[1],1.05+0.65*p[2],[shade,shade,shade],p[2].min(0.15)/0.15);}}
  let route=frame.route;
  // A neutral destination remains visible before the connection is established.
  if route.opacity==0. {if let Some(p)=frame.to {let p=project(p,&m);let fade=horizon_fade(p[2]);if fade>0. {surface_dot(pixels,C+R*p[0],C-R*p[1],1.6,self.ink.idle,0.5*fade);}}}
  let complete=frame.from.is_some()&&frame.to.is_some();
  if route.opacity>0. {if let(Some(a),Some(b))=(frame.from,frame.to) {
   // Adaptive spacing avoids both gaps on long routes and opaque overlap on
   // short routes, so the same opacity curve produces a true soft reveal.
   let angle=dot3(a,b).clamp(-1.,1.).acos();let steps=(angle*R*route.reveal/1.25).ceil().max(1.) as usize;
   for i in 0..=steps {let t=i as f32/steps as f32*route.reveal;let p=project(arc(a,b,t),&m);let fade=horizon_fade(p[2])*route.opacity;if fade>0. {let(x,y)=(C+R*p[0],C-R*p[1]);surface_dot(pixels,x,y,2.8,self.ink.route,0.045*fade);surface_dot(pixels,x,y,1.05,self.ink.route,0.70*fade);}}
   // The reveal head settles into the destination. The quieter travelling
   // highlight starts only after arrival, with a separate crossfade.
   for (t,alpha) in [(route.reveal,1.-route.arrival),(frame.phase,0.8*route.flow)] {
    let p=project(arc(a,b,t),&m);let fade=horizon_fade(p[2])*route.opacity*alpha;
    if fade>0. {let(x,y)=(C+R*p[0],C-R*p[1]);surface_dot(pixels,x,y,6.,self.ink.head.0,0.07*fade);surface_dot(pixels,x,y,2.2,self.ink.head.1,fade);}
   }
  }}
  for(point,color,alpha)in[(frame.from,self.ink.origin,route.opacity),(frame.to,if self.ink.route==DARK.route {[61,202,160]}else{[18,140,102]},route.opacity*route.arrival)] {if let Some(p)=point {let p=project(p,&m);let fade=horizon_fade(p[2])*alpha*if complete{1.}else{0.};if fade>0. {let(x,y)=(C+R*p[0],C-R*p[1]);surface_dot(pixels,x,y,8.,color,0.10*fade);surface_dot(pixels,x,y,4.5,color,0.22*fade);surface_dot(pixels,x,y,2.5,color,fade);}}}
 }
}
fn dot(pixels:&mut[u8],x:f32,y:f32,r:f32,color:[u8;3],opacity:f32) {
 paint_dot(pixels,x,y,r,color,opacity,false);
}
fn horizon_fade(z:f32)->f32 {let t=((z-0.06)/0.20).clamp(0.,1.);t*t*(3.-2.*t)}
fn surface_dot(pixels:&mut[u8],x:f32,y:f32,r:f32,color:[u8;3],opacity:f32) {
 paint_dot(pixels,x,y,r,color,opacity,true);
}
fn paint_dot(pixels:&mut[u8],x:f32,y:f32,r:f32,color:[u8;3],opacity:f32,clip:bool) {
 let r=r*K;
 for yy in ((y-r-1.).floor() as i32).max(0)..=((y+r+1.).ceil() as i32).min(SIZE as i32-1) {for xx in ((x-r-1.).floor() as i32).max(0)..=((x+r+1.).ceil() as i32).min(SIZE as i32-1) {let distance=((xx as f32-x).powi(2)+(yy as f32-y).powi(2)).sqrt();let mask=if clip {((R+0.5)-((xx as f32-C).powi(2)+(yy as f32-C).powi(2)).sqrt()).clamp(0.,1.)}else{1.};let a=(r+0.6-distance).clamp(0.,1.)*opacity*mask;if a>0. {let i=(yy as usize*SIZE+xx as usize)*4;for c in 0..3 {pixels[i+c]=(pixels[i+c] as f32*(1.-a)+color[c] as f32*a) as u8;}pixels[i+3]=pixels[i+3].max((a*255.) as u8);}}}
}

#[cfg(test)]
mod route_edge_tests {
 use super::*;
 #[test]
 fn intro_reveals_more_of_the_route_and_disconnect_clears_it() {
  let renderer=Renderer::new();let mut animation=RouteAnimation::new();
  let mut coverage=Vec::new();
  for now in [0.,0.2,0.6,1.7,2.,2.4] {
   let state=if now<2. {"connected"}else{"disconnected"};
   let route=animation.sample(now,"RU:DE",state,true);
   let pixels=renderer.render(Frame{rotation:home(),from:Some(unit(56.,38.)),to:Some(unit(51.,10.)),route,phase:animation.phase(now)});
   coverage.push(pixels.chunks_exact(4).filter(|p|p[1] as i32>p[0] as i32+10 && p[1] as i32>p[2] as i32+10).count());
  }
  assert_eq!(coverage[0],0);assert!(coverage[1]>0);
  assert!(coverage[2]>coverage[1] && coverage[3]>coverage[2],"reveal coverage: {coverage:?}");
  assert!(coverage[4]>0);assert_eq!(coverage[5],0);
 }
 #[test]
 fn route_and_markers_stay_inside_the_silhouette() {
  let renderer=Renderer::new();let mut visible=0;
  for step in 0..12 {
   let angle=step as f32*std::f32::consts::PI/6.;
   let pixels=renderer.render(Frame{rotation:[(angle/2.).cos(),0.,(angle/2.).sin(),0.],from:Some(unit(70.,-30.)),to:Some(unit(70.,30.)),route:RouteVisual::complete(true),phase:0.5});
   for (i,p) in pixels.chunks_exact(4).enumerate() {
    if p[1] as i32>p[0] as i32+12 && p[1] as i32>p[2] as i32+12 {
     visible+=1;let x=(i%SIZE) as f32-C;let y=(i/SIZE) as f32-C;
     assert!(x*x+y*y<=(R+0.5)*(R+0.5),"route leaked outside globe at {x},{y}, rotation {step}");
    }
   }
  }
  assert!(visible>100,"route unexpectedly disappeared");
 }
 #[test]
 fn horizon_fade_is_continuous_and_hides_backside() {
  assert_eq!(horizon_fade(-0.5),0.);assert_eq!(horizon_fade(0.06),0.);assert_eq!(horizon_fade(0.3),1.);
  let mut previous=0.;for i in 0..100 {let fade=horizon_fade(i as f32/100.);assert!(fade>=previous);previous=fade;}
 }
 // Isolated renderer fixtures, not simulated VPN sessions or product screenshots.
 #[test] #[ignore]
 fn export_route_edge_fixtures() {
  let folder=std::path::PathBuf::from(std::env::var("KAWA_GLOBE_EDGE_DIR").expect("fixture output directory"));
  std::fs::create_dir_all(&folder).unwrap();let renderer=Renderer::new();
  for (name,rotation,from,to) in [
   ("europe",home(),unit(56.,38.),unit(51.,10.)),
   ("horizon",[1.,0.,0.,0.],unit(70.,-30.),unit(70.,30.)),
   ("americas",mul(home(),[0.731689,0.,0.681639,0.]),unit(40.,-100.),unit(-15.,-55.)),
   ("australia",mul(home(),[0.540302,0.,-0.841471,0.]),unit(-20.,135.),unit(-35.,150.))] {
   let pixels=renderer.render(Frame{rotation,from:Some(from),to:Some(to),route:RouteVisual::complete(true),phase:0.5});
   let file=std::fs::File::create(folder.join(format!("{name}.png"))).unwrap();
   let mut encoder=png::Encoder::new(file,SIZE as u32,SIZE as u32);encoder.set_color(png::ColorType::Rgba);encoder.set_depth(png::BitDepth::Eight);
   encoder.write_header().unwrap().write_image_data(&pixels).unwrap();
  }
  // Native renderer sequence for visual QA. Fixtures never alter the app's
  // connection state or substitute fabricated subscription/server data.
  for (name,from,to) in [("intro-europe",unit(56.,38.),unit(51.,10.)),("intro-atlantic",unit(39.,-98.),unit(51.,10.))] {
   let mut animation=RouteAnimation::new();
   for i in 0..96 {
    let now=i as f64/30.;let state=if now<0.5 {"connecting"}else if now<2.4 {"connected"}else{"disconnected"};
    let route=animation.sample(now,name,state,true);
    let pixels=renderer.render(Frame{rotation:home(),from:Some(from),to:Some(to),route,phase:animation.phase(now)});
    let file=std::fs::File::create(folder.join(format!("{name}-{i:03}.png"))).unwrap();
    let mut encoder=png::Encoder::new(file,SIZE as u32,SIZE as u32);encoder.set_color(png::ColorType::Rgba);encoder.set_depth(png::BitDepth::Eight);
    encoder.write_header().unwrap().write_image_data(&pixels).unwrap();
   }
  }
 }
}
pub fn install(ui:&AppWindow) {
 let camera=Rc::new(RefCell::new(Camera::new()));
 ui.on_globe_drag({let c=camera.clone();move|x,y,down|c.borrow_mut().drag(x,y,down)});
 ui.on_globe_home({let c=camera.clone();move||c.borrow_mut().go_home()});
 install_origin_probe(ui);
 let renderers=[Renderer::themed(false),Renderer::themed(true)];
 let weak=ui.as_weak();let mut previous=String::new();let start=Instant::now();let mut last_tick=Instant::now();
 let mut animation=RouteAnimation::new();let mut last_visual=RouteVisual::hidden();let mut route_points=(None,None);
 let timer=Box::leak(Box::new(slint::Timer::default()));
 // ~120 Hz tick: frames are produced only when something moved, and the
 // smoothing is time-based, so the cadence matches the display on 60–144 Hz.
 timer.start(slint::TimerMode::Repeated,Duration::from_millis(8),move||{
  let Some(ui)=weak.upgrade()else{return;};let state=ui.get_conn_state();let active=matches!(state.as_str(),"connected"|"connecting");
  let destination=if state=="connected"&&!ui.get_exit_country().is_empty(){ui.get_exit_country()}else{ui.get_destination_country()};
  let motion=ui.global::<Theme>().get_motion();let now=start.elapsed().as_secs_f64();let key=format!("{}:{}",ui.get_origin_country(),destination);
  let dt=last_tick.elapsed().as_secs_f32().min(0.1);last_tick=Instant::now();
  let route=animation.sample(now,&key,state.as_str(),motion);let changed=route!=last_visual;last_visual=route;
  ui.set_route_reveal(route.reveal);ui.set_route_alpha(route.opacity);ui.set_route_arrival(route.arrival);ui.set_route_phase(if motion && route.opacity>0. {animation.phase(now)}else{0.});
  let point=|code:&str|region(code).map(|(_,lat,lon)|unit(lat,lon));
  // Keep the retiring route's endpoints while it fades, even if a different
  // server is selected immediately after disconnecting.
  if active {route_points=(point(ui.get_origin_country().as_str()),point(destination.as_str()));}
  if ui.get_view_page()!=0||ui.get_settings_open()||ui.get_routing_open()||ui.get_editor_open()||!ui.window().is_visible(){return;}
  let light=ui.global::<Theme>().get_light();
  let mut camera=camera.borrow_mut();let signature=format!("{key}:{active}:{light}");if signature!=previous||changed {camera.dirty=true;previous=signature;}
  let animated=motion&&(active||route.opacity>0.);
  if !camera.tick(motion,dt)&&!animated{return;}
  let (from,to)=if active||route.opacity>0. {route_points}else{(point(ui.get_origin_country().as_str()),point(destination.as_str()))};
  let mut buffer=SharedPixelBuffer::<Rgba8Pixel>::new(SIZE as u32,SIZE as u32);
  renderers[light as usize].render_into(buffer.make_mut_bytes(),Frame{rotation:camera.rotation,from,to,route,phase:animation.phase(now)});
  ui.set_globe_image(Image::from_rgba8(buffer));camera.dirty=false;
 });
}

#[cfg(test)]mod motion_tests {
 use super::*;
 #[test]fn dragging_right_moves_the_grabbed_surface_right(){let a=ball(0.5,0.5);let b=ball(0.65,0.5);let moved=project(a,&matrix(between(a,b)));for i in 0..3{assert!((moved[i]-b[i]).abs()<0.0001);}}
 #[test]fn dragging_down_moves_surface_down(){let a=ball(0.5,0.5);let b=ball(0.5,0.7);let p=project(a,&matrix(between(a,b)));assert!(p[1]<0.);assert!((p[1]-b[1]).abs()<0.0001);}
 #[test]fn press_release_and_idle_never_jump_or_spin(){let mut c=Camera::new();let before=c.rotation;c.drag(0.85,0.4,true);c.tick(true,0.008);assert_eq!(c.rotation,before);c.drag(0.7,0.45,true);c.tick(true,0.008);assert_ne!(c.rotation,before);c.drag(0.,0.,false);for _ in 0..100{c.tick(true,0.008);}let stopped=c.rotation;assert_eq!(stopped,c.target,"settles exactly on the pointer's orientation");for _ in 0..100{c.tick(true,0.008);}assert_eq!(c.rotation,stopped);}
 #[test]fn smoothing_is_monotonic_and_frame_rate_independent(){
  let run=|step:f32|{let mut c=Camera::new();c.drag(0.5,0.5,true);c.drag(0.8,0.5,true);let mut d=Vec::new();let mut t=0.;while t<0.2-1e-4{c.tick(true,step);t+=step;d.push(angle_between(c.rotation,c.target));}d};
  let fast=run(1./144.);let slow=run(1./60.);
  for w in fast.windows(2){assert!(w[1]<=w[0]+1e-6,"overshoot or reversal");}
  assert!((fast.last().unwrap()-slow.last().unwrap()).abs()<0.01,"60 Hz and 144 Hz end in different places");
  assert!(*fast.last().unwrap()<0.02,"lags behind the pointer");
 }
 #[test]fn no_motion_means_no_easing(){let mut c=Camera::new();c.drag(0.5,0.5,true);c.drag(0.8,0.5,true);c.tick(false,0.008);assert_eq!(c.rotation,c.target);}
 #[test]fn many_drags_preserve_a_rigid_sphere(){let mut c=Camera::new();for i in 0..500{c.drag(0.5+0.45*(i as f32*0.07).sin(),0.5+0.45*(i as f32*0.05).cos(),true);}let p=project(unit(52.,5.),&matrix(c.rotation));assert!((dot3(p,p)-1.).abs()<0.0001);}
 #[test]fn opposite_arcball_points_have_a_valid_rotation(){let a=[1.,0.,0.];let b=[-1.,0.,0.];let p=project(a,&matrix(between(a,b)));assert!((p[0]+1.).abs()<0.0001);}
 #[test]#[ignore="explicit native frame timing probe"]fn render_performance_probe(){let r=Renderer::new();let mut timings=Vec::new();for i in 0..180{let started=Instant::now();let pixels=r.render(Frame{rotation:mul(home(),[(i as f32*0.01).cos(),0.,(i as f32*0.01).sin(),0.]),from:Some(unit(56.,38.)),to:Some(unit(51.,10.)),route:RouteVisual::complete(true),phase:i as f32/180.});std::hint::black_box(pixels);timings.push(started.elapsed().as_secs_f64()*1000.);}timings.sort_by(f64::total_cmp);println!("GLOBE_FRAME_MS median={:.3} p95={:.3} max={:.3}",timings[90],timings[171],timings[179]);assert!(timings[171]<16.,"CPU render exceeds the 60 Hz frame budget");}
}

pub fn region(code:&str)->Option<(&'static str,f32,f32)> {Some(match code {
 "RU"=>("Россия",56.,38.),"DE"=>("Германия",51.,10.),"NL"=>("Нидерланды",52.,5.),"SE"=>("Швеция",61.,16.),"FI"=>("Финляндия",64.,26.),"FR"=>("Франция",47.,2.),"GB"=>("Великобритания",54.,-2.),"US"=>("США",39.,-98.),"CA"=>("Канада",55.,-105.),"TR"=>("Турция",39.,35.),"PL"=>("Польша",52.,19.),"EE"=>("Эстония",59.,26.),"LV"=>("Латвия",57.,25.),"LT"=>("Литва",55.,24.),"CH"=>("Швейцария",47.,8.),"AT"=>("Австрия",48.,14.),"ES"=>("Испания",40.,-4.),"IT"=>("Италия",43.,12.),"UA"=>("Украина",49.,32.),"KZ"=>("Казахстан",48.,68.),"BY"=>("Беларусь",54.,28.),"GE"=>("Грузия",42.,44.),"AM"=>("Армения",40.,45.),"JP"=>("Япония",36.,138.),"SG"=>("Сингапур",1.,104.),"AU"=>("Австралия",-25.,134.),"HK"=>("Гонконг",22.,114.),"KR"=>("Южная Корея",36.,128.),"IL"=>("Израиль",31.,35.),"AE"=>("ОАЭ",24.,54.),"BR"=>("Бразилия",-14.,-52.),_=>return None})}

pub(crate) fn trace_country(body:&str)->Option<String> {
 let code=body.lines().find_map(|l|l.strip_prefix("loc="))?;
 (code.len()==2 && code.bytes().all(|b|b.is_ascii_uppercase()) && code!="XX").then(||code.to_owned())
}
pub fn probe_exit(ui:&AppWindow,proxy:String,epoch:std::sync::Arc<std::sync::atomic::AtomicU64>,id:u64) {
 let weak=ui.as_weak();std::thread::spawn(move||{
  let probe=||->Option<String>{let proxy=ureq::Proxy::new(&proxy).ok()?;let agent:ureq::Agent=ureq::Agent::config_builder().proxy(Some(proxy)).timeout_global(Some(Duration::from_secs(7))).build().into();let body=agent.get("https://www.cloudflare.com/cdn-cgi/trace").call().ok()?.body_mut().read_to_string().ok()?;trace_country(&body)};
  let mut found=None;
  for delay in [0,2,8,30] {
   if epoch.load(std::sync::atomic::Ordering::Relaxed)!=id{return;}
   if delay>0 {std::thread::sleep(Duration::from_secs(delay));}
   if epoch.load(std::sync::atomic::Ordering::Relaxed)!=id{return;}
   found=probe();if found.is_some(){break;}
  }
  if let Some(code)=found{let _=slint::invoke_from_event_loop(move||{if epoch.load(std::sync::atomic::Ordering::Relaxed)!=id{return;}if let Some(ui)=weak.upgrade(){if ui.get_conn_state()=="connected" {let image=crate::flag_image(&code);ui.set_exit_has_flag(image.is_some());ui.set_exit_flag(image.unwrap_or_default());ui.set_exit_country(code.clone().into());ui.set_exit_label(region(&code).map(|(name,_,_)|name).unwrap_or(&code).into());}}});}
 });
}

fn install_origin_probe(ui:&AppWindow){
 if ["KAWA_DOT_QA","KAWA_ROUTING_QA"].iter().any(|key|std::env::var_os(key).is_some()){return;}
 schedule_origin_probe(ui.as_weak(),0);
}
fn schedule_origin_probe(weak:slint::Weak<AppWindow>,attempt:u32){
 std::thread::spawn(move||{
  let country=crate::origin_geo::probe_country();
  let _=slint::invoke_from_event_loop(move||{
   let Some(ui)=weak.upgrade()else{return;};
   let next=if let Some(code)=country {
    ui.set_origin_country(code.clone().into());
    ui.set_origin_label(region(&code).unwrap().0.into());ui.set_origin_status("".into());
    (300,0)
   }else{
    if ui.get_origin_country().is_empty(){ui.set_origin_status("Страна устройства недоступна — повторяем проверку".into());}
    ([2,8,30][attempt.min(2) as usize],attempt.saturating_add(1))
   };
   let weak=ui.as_weak();
   slint::Timer::single_shot(Duration::from_secs(next.0),move||schedule_origin_probe(weak,next.1));
  });
 });
}
#[cfg(test)] mod tests {
 use super::*;
 #[test]fn projection_preserves_unit_length(){let p=project(unit(52.,5.),&matrix(home()));assert!((p.iter().map(|x|x*x).sum::<f32>()-1.).abs()<0.0001);}
 #[test]fn great_circle_has_correct_endpoints_and_surface(){let a=unit(56.,38.);let b=unit(51.,10.);for(t,expected)in[(0.,a),(1.,b)]{let p=arc(a,b,t);assert!((p[0]-expected[0]).abs()<0.0001);}let p=arc(a,b,0.5);assert!((p.iter().map(|x|x*x).sum::<f32>()-1.).abs()<0.0001);}
 #[test]fn unknown_regions_do_not_invent_a_location(){assert!(region("EU").is_none());assert!(region("").is_none());assert_eq!(region("NL").unwrap().0,"Нидерланды");}
 #[test] #[ignore="explicit real country probes and isolated renderer export; never changes the VPN"]
 fn live_physical_to_current_exit_fixture(){
  assert_eq!(std::env::var("KAWA_LIVE_ORIGIN_TEST").as_deref(),Ok("1"));
  let origin=crate::origin_geo::probe_country().expect("physical origin unavailable");
  if let Ok(expected)=std::env::var("KAWA_EXPECT_ORIGIN_COUNTRY"){assert_eq!(origin,expected);}
  let agent:ureq::Agent=ureq::Agent::config_builder().proxy(None).timeout_global(Some(Duration::from_secs(7))).build().into();
  let body=agent.get("https://www.cloudflare.com/cdn-cgi/trace").call().unwrap().body_mut().read_to_string().unwrap();
  let exit=trace_country(&body).expect("current network country unavailable");
  let (_,lat,lon)=region(&origin).unwrap();let (_,end_lat,end_lon)=region(&exit).unwrap();
  let pixels=Renderer::new().render(Frame{rotation:home(),from:Some(unit(lat,lon)),to:Some(unit(end_lat,end_lon)),route:RouteVisual::complete(true),phase:0.5});
  let coverage=pixels.chunks_exact(4).filter(|p|p[1] as i32>p[0] as i32+10 && p[1] as i32>p[2] as i32+10).count();
  if origin!=exit {assert!(coverage>60,"live route collapsed: {coverage}");}
  let file=std::fs::File::create(std::env::var("KAWA_LIVE_ROUTE_IMAGE").expect("isolated renderer output path")).unwrap();
  let mut encoder=png::Encoder::new(file,SIZE as u32,SIZE as u32);encoder.set_color(png::ColorType::Rgba);encoder.set_depth(png::BitDepth::Eight);encoder.write_header().unwrap().write_image_data(&pixels).unwrap();
  println!("LIVE_ROUTE {origin} -> {exit}; colored pixels={coverage}");
 }
 #[test]fn unknown_origin_does_not_draw_a_misleading_connected_marker(){
  let renderer=Renderer::new();let route=RouteVisual::complete(true);
  let empty=renderer.render(Frame{rotation:home(),from:None,to:None,route,phase:0.5});
  let unknown_origin=renderer.render(Frame{rotation:home(),from:None,to:Some(unit(51.,10.)),route,phase:0.5});
  assert_eq!(empty,unknown_origin);
 }
 #[test]fn trace_parser_requires_a_country_and_does_not_keep_the_ip(){assert_eq!(trace_country("ip=1.2.3.4\nloc=NL\n"),Some("NL".into()));assert_eq!(trace_country("loc=XX\n"),None);assert_eq!(trace_country("loc=<html>\n"),None);assert_eq!(trace_country("ip=1.2.3.4\n"),None);}
}
#[cfg(test)] mod theme_tests {
 use super::*;
 #[test]fn light_theme_globe_is_pale_with_dark_dots(){
  let f=Frame{rotation:home(),from:None,to:None,route:RouteVisual::hidden(),phase:0.};
  let (d,l)=(Renderer::themed(false).render(f),Renderer::themed(true).render(f));
  let center=(SIZE/2*SIZE+SIZE/2)*4;
  assert!(d[center]<60 && l[center]>190,"sphere fill: dark {} light {}",d[center],l[center]);
  let darkest=l.chunks_exact(4).filter(|p|p[3]==255).map(|p|p[0]).min().unwrap();
  assert!(darkest<110,"light globe needs visible graphite dots, darkest {darkest}");
 }
}
