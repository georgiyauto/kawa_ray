//! Cancellation-safe, bounded framing for untrusted local clients.
use duoray_core::helper_proto::MAX_MESSAGE_BYTES;
use std::{io, time::Duration};
use tokio::{
    io::{AsyncBufRead, AsyncBufReadExt},
    time::Instant,
};

pub struct Frames<R> {
    reader: R,
    bytes: Vec<u8>,
    deadline: Option<Instant>,
    idle: Duration,
    partial: Duration,
}

impl<R: AsyncBufRead + Unpin> Frames<R> {
    pub fn new(reader: R) -> Self {
        Self {
            reader,
            bytes: Vec::new(),
            deadline: None,
            idle: Duration::from_secs(30),
            partial: Duration::from_secs(5),
        }
    }

    #[cfg(test)]
    pub fn with_deadlines(reader: R, idle: Duration, partial: Duration) -> Self {
        Self {
            reader,
            bytes: Vec::new(),
            deadline: None,
            idle,
            partial,
        }
    }

    pub async fn next(&mut self) -> io::Result<Option<String>> {
        loop {
            let deadline = *self
                .deadline
                .get_or_insert_with(|| Instant::now() + self.idle);
            let available = tokio::time::timeout_at(deadline, self.reader.fill_buf())
                .await
                .map_err(|_| {
                    io::Error::new(io::ErrorKind::TimedOut, "IPC frame deadline exceeded")
                })??;
            if available.is_empty() {
                if self.bytes.is_empty() {
                    return Ok(None);
                }
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "incomplete IPC frame",
                ));
            }
            let count = available
                .iter()
                .position(|&b| b == b'\n')
                .map_or(available.len(), |n| n + 1);
            if self.bytes.len() + count > MAX_MESSAGE_BYTES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "IPC message too large",
                ));
            }
            if self.bytes.is_empty() {
                self.deadline = Some(Instant::now() + self.partial);
            }
            let complete = available[count - 1] == b'\n';
            self.bytes.extend_from_slice(&available[..count]);
            self.reader.consume(count);
            if complete {
                self.deadline = None;
                return String::from_utf8(std::mem::take(&mut self.bytes))
                    .map(Some)
                    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncWriteExt, BufReader};

    #[tokio::test]
    async fn rejects_oversized_before_parsing() {
        let data = vec![b'a'; MAX_MESSAGE_BYTES + 1];
        let mut frames = Frames::new(BufReader::new(data.as_slice()));
        assert_eq!(
            frames.next().await.unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[tokio::test]
    async fn partial_frame_has_deadline_and_preserves_state_on_cancellation() {
        let (mut writer, reader) = tokio::io::duplex(128);
        let mut frames = Frames::new(BufReader::new(reader));
        frames.partial = Duration::from_millis(40);
        writer.write_all(b"{\"cmd\":").await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(10), frames.next())
                .await
                .is_err()
        );
        writer.write_all(b"\"hello\"}\n").await.unwrap();
        assert_eq!(
            frames.next().await.unwrap().unwrap(),
            "{\"cmd\":\"hello\"}\n"
        );
        writer.write_all(b"{").await.unwrap();
        assert_eq!(
            frames.next().await.unwrap_err().kind(),
            io::ErrorKind::TimedOut
        );
    }

    #[tokio::test]
    async fn reads_consecutive_frames_and_clean_eof() {
        let mut frames = Frames::new(BufReader::new(&b"one\ntwo\n"[..]));
        assert_eq!(frames.next().await.unwrap().unwrap(), "one\n");
        assert_eq!(frames.next().await.unwrap().unwrap(), "two\n");
        assert!(frames.next().await.unwrap().is_none());
    }
}
