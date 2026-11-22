; Set KAWA_SIGN_COMMAND to a release-machine signing wrapper that accepts one
; file argument and fails unless both Authenticode and timestamp verify.
!ifdef KAWA_SIGN_COMMAND
  !uninstfinalize '${KAWA_SIGN_COMMAND} "%1"' = 0
  !finalize '${KAWA_SIGN_COMMAND} "%1"' = 0
!else
  !warning "Unsigned development installer: public publishing will reject it."
!endif
