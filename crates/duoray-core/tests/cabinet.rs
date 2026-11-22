use duoray_core::cabinet::{Api, Checkout, Purchase};
use serde_json::{Value, json};
use std::{
    io::{Read, Write},
    net::TcpListener,
    sync::{Arc, Mutex},
};

fn server(
    responses: Vec<(u16, Value)>,
) -> (Api, Arc<Mutex<Vec<String>>>, std::thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let api = Api::test_endpoint(format!("http://{}", listener.local_addr().unwrap()));
    let requests = Arc::new(Mutex::new(Vec::new()));
    let capture = requests.clone();
    let handle = std::thread::spawn(move || {
        for (status, body) in responses {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut request = Vec::new();
            let mut buf = [0; 4096];
            loop {
                let n = stream.read(&mut buf).unwrap();
                request.extend_from_slice(&buf[..n]);
                let text = String::from_utf8_lossy(&request);
                if let Some(pos) = text.find("\r\n\r\n") {
                    let length: usize = text[..pos]
                        .lines()
                        .find_map(|line| {
                            line.to_ascii_lowercase()
                                .strip_prefix("content-length: ")
                                .and_then(|v| v.parse().ok())
                        })
                        .unwrap_or(0);
                    if request.len() >= pos + 4 + length {
                        break;
                    }
                }
            }
            capture
                .lock()
                .unwrap()
                .push(String::from_utf8(request).unwrap());
            let body = body.to_string();
            write!(stream, "HTTP/1.1 {status} response\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
        }
    });
    (api, requests, handle)
}

#[test]
fn renewal_pins_subscription_and_platega_only_funds_missing_balance() {
    let (api, requests, h) = server(vec![
        (
            402,
            json!({"detail":{"code":"insufficient_funds","missing_amount":500,"cart_saved":true}}),
        ),
        (
            200,
            json!({"payment_url":"https://pay.platega.io/test","payment_id":"invoice","amount_kopeks":1000}),
        ),
    ]);
    let result = api
        .checkout(
            "secret",
            &Purchase {
                tariff_id: 1,
                period_days: 30,
                subscription_id: Some(399),
            },
            "2",
            1000,
            None,
        )
        .unwrap();
    assert!(matches!(result, Checkout::Invoice { .. }));
    h.join().unwrap();
    let req = requests.lock().unwrap();
    let purchase: Value = serde_json::from_str(req[0].split("\r\n\r\n").nth(1).unwrap()).unwrap();
    assert_eq!(purchase["subscription_id"], 399);
    let topup: Value = serde_json::from_str(req[1].split("\r\n\r\n").nth(1).unwrap()).unwrap();
    assert_eq!(topup["amount_kopeks"], 1000);
    assert_eq!(topup["payment_method"], "platega");
    assert_eq!(topup["payment_option"], "2");
}

#[test]
fn funded_purchase_does_not_create_invoice() {
    let (api, requests, h) = server(vec![(200, json!({"id":99,"end_date":"2026-11-06"}))]);
    assert!(matches!(
        api.checkout(
            "secret",
            &Purchase {
                tariff_id: 1,
                period_days: 30,
                subscription_id: None
            },
            "2",
            1000,
            None
        )
        .unwrap(),
        Checkout::Fulfilled(_)
    ));
    h.join().unwrap();
    assert_eq!(requests.lock().unwrap().len(), 1);
}

#[test]
fn hosted_checkout_keeps_order_identity_and_provider_page_selection() {
    let (api, requests, h) = server(vec![
        (402, json!({"detail":{"code":"insufficient_funds","missing_amount":7500,"cart_saved":true}})),
        (200, json!({"payment_url":"https://pay.platega.io/?id=hosted","payment_id":"hosted"})),
    ]);
    let id = "a".repeat(32);
    let result = api.checkout_for_order("secret", &Purchase {
        tariff_id: 1, period_days: 30, subscription_id: Some(399),
    }, "hosted", 1000, Some(500000), &id).unwrap();
    assert!(matches!(result, Checkout::Invoice { payment_id, amount: 7500, .. } if payment_id == "hosted"));
    h.join().unwrap();
    let requests = requests.lock().unwrap();
    assert_eq!(requests.len(), 2);
    let topup: Value = serde_json::from_str(requests[1].split("\r\n\r\n").nth(1).unwrap()).unwrap();
    assert_eq!(topup["payment_option"], "hosted");
    assert_eq!(topup["checkout_id"], id);
}

#[test]
fn unsaved_cart_never_creates_invoice() {
    let (api, _, h) = server(vec![(
        402,
        json!({"detail":{"code":"insufficient_funds","missing_amount":7500,"cart_saved":false}}),
    )]);
    assert!(
        api.checkout(
            "secret",
            &Purchase {
                tariff_id: 1,
                period_days: 30,
                subscription_id: None
            },
            "2",
            1000,
            None
        )
        .is_err()
    );
    h.join().unwrap();
}

#[test]
fn failed_mutation_is_not_retried_and_error_hides_credentials() {
    let (api, requests, h) = server(vec![(
        500,
        json!({"detail":"secret and private subscription URL"}),
    )]);
    let error = api
        .checkout(
            "secret",
            &Purchase {
                tariff_id: 1,
                period_days: 30,
                subscription_id: None,
            },
            "2",
            1000,
            None,
        )
        .unwrap_err()
        .to_string();
    h.join().unwrap();
    assert_eq!(requests.lock().unwrap().len(), 1);
    assert!(!error.contains("secret"));
}

#[test]
fn pending_login_is_not_authentication() {
    let (api, _, h) = server(vec![(202, json!({"detail":"Waiting for confirmation"}))]);
    assert!(api.poll_login("one-time").unwrap().is_none());
    h.join().unwrap();
}

#[test]
fn rejected_purchase_is_distinguishable_from_uncertain_network_outcome() {
    let (api, _, h) = server(vec![(401, json!({"detail":"expired"}))]);
    let error = api
        .checkout(
            "secret",
            &Purchase {
                tariff_id: 1,
                period_days: 30,
                subscription_id: None,
            },
            "2",
            1000,
            None,
        )
        .unwrap_err();
    assert!(
        error
            .downcast_ref::<duoray_core::cabinet::RejectedPurchase>()
            .is_some()
    );
    h.join().unwrap();
}

#[test]
fn expired_access_is_refreshed_once_before_retrying_read() {
    let (api, requests, h) = server(vec![
        (401, json!({"detail":"expired"})),
        (
            200,
            json!({"access_token":"new-access","refresh_token":"new-refresh","user":{"id":1}}),
        ),
        (200, json!({"id":1})),
        (200, json!({"id":1})),
        (200, json!({"subscriptions":[]})),
        (200, json!({"tariffs":[]})),
        (200, json!([])),
    ]);
    let mut session = duoray_core::cabinet::Session {
        access_token: "expired".into(),
        refresh_token: "valid-refresh".into(),
        user: json!({"id":1}),
    };
    assert!(api.snapshot_refreshed(&mut session, None).is_ok());
    assert_eq!(session.refresh_token, "new-refresh");
    h.join().unwrap();
    let requests = requests.lock().unwrap();
    assert!(requests[1].starts_with("POST /auth/refresh"));
    assert!(requests[2].contains("Bearer new-access"));
    assert_eq!(requests.len(), 7);
}

#[test]
fn receipt_recovery_refreshes_access_and_preserves_rotated_token_on_read_error() {
    let (api, requests, h) = server(vec![
        (401, json!({"detail":"expired"})),
        (
            200,
            json!({"access_token":"new-access","refresh_token":"new-refresh","user":{"id":1}}),
        ),
        (200, json!({"id":1})),
        (401, json!({"detail":"rejected"})),
    ]);
    let mut session = duoray_core::cabinet::Session {
        access_token: "expired".into(),
        refresh_token: "valid-refresh".into(),
        user: json!({"id":1}),
    };
    let error = api
        .order_refreshed(&mut session, &"a".repeat(32))
        .unwrap_err();
    assert!(
        error
            .downcast_ref::<duoray_core::cabinet::SessionExpired>()
            .is_none()
    );
    assert_eq!(session.refresh_token, "new-refresh");
    h.join().unwrap();
    let requests = requests.lock().unwrap();
    assert_eq!(requests.len(), 4);
    assert!(requests[3].contains("Bearer new-access"));
}

#[test]
fn refresh_accepts_backend_token_pair_and_loads_authenticated_owner() {
    let (api, requests, h) = server(vec![
        (200, json!({"access_token":"new","refresh_token":"same","token_type":"bearer","expires_in":900})),
        (200, json!({"id":7,"first_name":"Owner"})),
    ]);
    let result = api.refresh("same");
    assert!(result.is_ok(), "backend TokenResponse must restore login");
    let session = result.unwrap();
    assert_eq!(session.user["id"],7);
    h.join().unwrap();
    let requests = requests.lock().unwrap();
    assert!(requests[1].starts_with("GET /auth/me"));
    assert!(requests[1].contains("Bearer new"));
}

#[test]
fn pending_invoice_is_verified_without_recreating_checkout() {
    let (api, requests, h) = server(vec![
        (200, json!({"id":17,"is_paid":false,"is_checkable":true,"status":"PENDING"})),
        (200, json!({"success":true,"payment":{"id":17,"is_paid":true,"status":"CONFIRMED"}})),
    ]);
    let mut session = duoray_core::cabinet::Session {
        access_token:"access".into(),refresh_token:"refresh".into(),user:json!({"id":7})
    };
    let payment=api.order_refreshed(&mut session,&"a".repeat(32)).unwrap().unwrap();
    assert_eq!(payment["is_paid"],true);
    h.join().unwrap();
    let requests=requests.lock().unwrap();
    assert_eq!(requests.len(),2);
    assert!(requests[1].starts_with("POST /balance/pending-payments/platega/17/check"));
    assert!(!requests.iter().any(|r|r.contains("/topup")||r.contains("purchase-tariff")));
}
#[test]
fn confirmed_invoice_is_not_rechecked() {
    let (api,requests,h)=server(vec![(200,json!({"id":17,"is_paid":true,"is_checkable":true}))]);
    let mut session=duoray_core::cabinet::Session {access_token:"a".into(),refresh_token:"r".into(),user:json!({"id":7})};
    assert!(api.order_refreshed(&mut session,&"a".repeat(32)).unwrap().is_some());
    h.join().unwrap();assert_eq!(requests.lock().unwrap().len(),1);
}

#[test]
fn temporary_rejection_of_new_access_does_not_invalidate_saved_refresh() {
    let (api,_,h)=server(vec![
        (200,json!({"access_token":"new","refresh_token":"same","expires_in":900})),
        (401,json!({"detail":"access temporarily rejected"})),
    ]);
    let error=api.refresh("same").err().unwrap();
    assert!(error.downcast_ref::<duoray_core::cabinet::SessionExpired>().is_none());
    h.join().unwrap();
}
