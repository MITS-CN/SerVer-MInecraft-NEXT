use std::io::{Read, Write};

use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use flate2::Compression;

// ============================================================
// VarInt / Packet
// ============================================================

pub fn read_varint<R: Read>(s: &mut R) -> std::io::Result<i32> {
    let mut value: u32 = 0;
    let mut shift: u32 = 0;
    loop {
        let mut b = [0u8; 1];
        s.read_exact(&mut b)?;
        value |= ((b[0] & 0x7F) as u32) << shift;
        if b[0] & 0x80 == 0 {
            return Ok(value as i32);
        }
        shift += 7;
        if shift >= 35 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "VarInt too long",
            ));
        }
    }
}

pub fn read_varint_slice(data: &[u8]) -> (i32, usize) {
    let mut value: u32 = 0;
    let mut shift: u32 = 0;
    for (i, &b) in data.iter().enumerate() {
        value |= ((b & 0x7F) as u32) << shift;
        if b & 0x80 == 0 {
            return (value as i32, i + 1);
        }
        shift += 7;
        if shift >= 35 {
            break;
        }
    }
    (0, 0)
}

pub fn write_varint(value: i32) -> Vec<u8> {
    let mut res = Vec::new();
    let mut v = value as u32;
    loop {
        let mut b = (v & 0x7F) as u8;
        v >>= 7;
        if v != 0 {
            b |= 0x80;
        }
        res.push(b);
        if v == 0 {
            break;
        }
    }
    res
}

pub fn read_packet<R: Read>(s: &mut R) -> std::io::Result<Vec<u8>> {
    let len = read_varint(s)?;
    if len <= 0 {
        return Ok(Vec::new());
    }
    let mut data = vec![0u8; len as usize];
    s.read_exact(&mut data)?;
    Ok(data)
}

/// 读包：区分「超时」和「其它错误」，外层用 Option 拿到超时分支
pub fn read_packet_opt<R: Read>(s: &mut R) -> Result<Option<Vec<u8>>, std::io::Error> {
    match read_packet(s) {
        Ok(p) => Ok(Some(p)),
        Err(e) => match e.kind() {
            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => Ok(None),
            _ => Err(e),
        },
    }
}

pub fn write_packet<W: Write>(s: &mut W, data: &[u8]) -> std::io::Result<()> {
    let len = write_varint(data.len() as i32);
    s.write_all(&len)?;
    s.write_all(data)?;
    s.flush()
}

pub fn wrap_packet(data: &[u8], threshold: i32) -> Vec<u8> {
    let mut inner = Vec::new();
    if threshold >= 0 {
        if data.len() >= threshold as usize {
            let mut e = ZlibEncoder::new(Vec::new(), Compression::fast());
            let _ = e.write_all(data);
            if let Ok(compressed) = e.finish() {
                inner.extend(write_varint(data.len() as i32));
                inner.extend(compressed);
            } else {
                inner.extend(write_varint(0));
                inner.extend(data);
            }
        } else {
            inner.extend(write_varint(0));
            inner.extend(data);
        }
    } else {
        inner.extend(data);
    }
    let mut out = write_varint(inner.len() as i32);
    out.extend(inner);
    out
}

pub fn unwrap_payload(pkt: &[u8], threshold: i32) -> Option<Vec<u8>> {
    if threshold < 0 {
        return Some(pkt.to_vec());
    }
    let (data_len, off) = read_varint_slice(pkt);
    if data_len == 0 {
        return Some(pkt[off..].to_vec());
    }
    let mut d = ZlibDecoder::new(&pkt[off..]);
    let mut out = Vec::new();
    d.read_to_end(&mut out).ok()?;
    Some(out)
}

// ============================================================
// Handshake
// ============================================================

#[derive(Debug, Clone)]
pub struct Handshake {
    pub protocol: i32,
    pub host: String,
    pub port: u16,
    pub next_state: i32,
}

/// Parse the handshake packet payload (the outer packet-length VarInt
/// has already been consumed by read_packet).
pub fn parse_handshake(data: &[u8]) -> Option<Handshake> {
    let (pid, mut pos) = read_varint_slice(data);
    if pos == 0 || pid != 0 {
        return None;
    }

    let (protocol, consumed) = read_varint_slice(&data[pos..]);
    if consumed == 0 {
        return None;
    }
    pos += consumed;

    let (addr_len, consumed) = read_varint_slice(&data[pos..]);
    if consumed == 0 || addr_len < 0 {
        return None;
    }
    pos += consumed;

    let host_len = addr_len as usize;
    let host_end = pos.checked_add(host_len)?;
    let port_end = host_end.checked_add(2)?;
    if port_end > data.len() {
        return None;
    }

    let host = String::from_utf8_lossy(&data[pos..host_end]).into_owned();
    let port = u16::from_be_bytes([data[host_end], data[host_end + 1]]);
    let (next_state, consumed) = read_varint_slice(&data[port_end..]);
    if consumed == 0 {
        return None;
    }

    Some(Handshake {
        protocol,
        host,
        port,
        next_state,
    })
}

pub fn next_state_name(ns: i32) -> &'static str {
    match ns {
        1 => "状态查询",
        2 => "登录",
        _ => "未知",
    }
}

// ============================================================
// 状态
// ============================================================

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ConnState {
    Login,
    Config,
    Play,
}

#[cfg(test)]
mod handshake_tests {
    use super::*;

    #[test]
    fn parses_handshake_payload_without_frame_length() {
        // Packet ID 0, protocol 765, host "localhost", port 25565, login state 2.
        let payload = [
            0x00, 0xFD, 0x05, 0x09, b'l', b'o', b'c', b'a', b'l', b'h', b'o', b's', b't',
            0x63, 0xDD, 0x02,
        ];

        let handshake = parse_handshake(&payload).expect("valid handshake");
        assert_eq!(handshake.protocol, 765);
        assert_eq!(handshake.host, "localhost");
        assert_eq!(handshake.port, 25565);
        assert_eq!(handshake.next_state, 2);
    }

    #[test]
    fn rejects_non_handshake_packet_id() {
        assert!(parse_handshake(&[0x01, 0x00, 0x00]).is_none());
    }

    #[test]
    fn rejects_truncated_handshake_payload() {
        assert!(parse_handshake(&[0x00, 0xFD, 0x05, 0x09, b'l']).is_none());
    }
}
