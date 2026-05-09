use std::env;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::process;

struct BaseUrl {
    host: String,
    port: u16,
    path: String,
}

struct Response {
    status: u16,
    content_type: String,
    body: Vec<u8>,
}

fn parse_base_url(value: &str) -> Result<BaseUrl, String> {
    let rest = value
        .strip_prefix("http://")
        .ok_or_else(|| "only http:// base URLs are supported".to_string())?;
    let (authority, path) = match rest.find('/') {
        Some(index) => (&rest[..index], &rest[index..]),
        None => (rest, ""),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) => (
            host.to_string(),
            port.parse::<u16>()
                .map_err(|_| format!("invalid port in base URL: {port}"))?,
        ),
        None => (authority.to_string(), 80),
    };
    Ok(BaseUrl {
        host,
        port,
        path: path.trim_end_matches('/').to_string(),
    })
}

fn post(
    base: &BaseUrl,
    path: &str,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Response, String> {
    let mut stream = TcpStream::connect((base.host.as_str(), base.port))
        .map_err(|err| format!("connect failed: {err}"))?;
    let full_path = format!("{}{}", base.path, path);
    let headers = format!(
        "POST {full_path} HTTP/1.1\r\n\
         Host: {}:{}\r\n\
         Content-Type: {content_type}\r\n\
         Accept: {accept}\r\n\
         x-request-id: rust-client-smoke\r\n\
         x-lance-tenant-id: rust-smoke\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\r\n",
        base.host,
        base.port,
        body.len()
    );
    stream
        .write_all(headers.as_bytes())
        .and_then(|_| stream.write_all(body))
        .map_err(|err| format!("request write failed: {err}"))?;

    let mut bytes = Vec::new();
    stream
        .read_to_end(&mut bytes)
        .map_err(|err| format!("response read failed: {err}"))?;
    parse_response(bytes)
}

fn parse_response(bytes: Vec<u8>) -> Result<Response, String> {
    let header_end = bytes
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .ok_or_else(|| "response missing header terminator".to_string())?;
    let header_text = String::from_utf8_lossy(&bytes[..header_end]);
    let mut lines = header_text.lines();
    let status_line = lines
        .next()
        .ok_or_else(|| "response missing status line".to_string())?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| format!("invalid status line: {status_line}"))?
        .parse::<u16>()
        .map_err(|_| format!("invalid status line: {status_line}"))?;
    let mut content_type = String::new();
    let mut chunked = false;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("content-type") {
                content_type = value.trim().to_string();
            } else if name.eq_ignore_ascii_case("transfer-encoding")
                && value.to_ascii_lowercase().contains("chunked")
            {
                chunked = true;
            }
        }
    }
    let raw_body = bytes[header_end + 4..].to_vec();
    let body = if chunked {
        decode_chunked(&raw_body)?
    } else {
        raw_body
    };
    Ok(Response {
        status,
        content_type,
        body,
    })
}

fn decode_chunked(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let mut index = 0;
    let mut body = Vec::new();
    loop {
        let line_end = find_crlf(bytes, index)
            .ok_or_else(|| "chunked response missing chunk size".to_string())?;
        let size_text = String::from_utf8_lossy(&bytes[index..line_end]);
        let size = usize::from_str_radix(size_text.split(';').next().unwrap_or(""), 16)
            .map_err(|_| format!("invalid chunk size: {size_text}"))?;
        index = line_end + 2;
        if size == 0 {
            return Ok(body);
        }
        if index + size + 2 > bytes.len() {
            return Err("chunked response ended mid-chunk".to_string());
        }
        body.extend_from_slice(&bytes[index..index + size]);
        index += size + 2;
    }
}

fn find_crlf(bytes: &[u8], start: usize) -> Option<usize> {
    bytes[start..]
        .windows(2)
        .position(|window| window == b"\r\n")
        .map(|offset| start + offset)
}

fn json_escape(value: &str) -> String {
    value.replace('\\', "\\\\").replace('"', "\\\"")
}

fn run() -> Result<(), String> {
    let mut base_url = None;
    let mut table_id = None;
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--base-url" => base_url = args.next(),
            "--table-id" => table_id = args.next(),
            other => return Err(format!("unknown argument: {other}")),
        }
    }
    let base = parse_base_url(&base_url.ok_or_else(|| "--base-url is required".to_string())?)?;
    let table_id = table_id.ok_or_else(|| "--table-id is required".to_string())?;
    let response = post(
        &base,
        &format!("/v1/table/{table_id}/query"),
        b"{}",
        "application/json",
        "application/vnd.apache.arrow.file",
    )?;
    let body_prefix = String::from_utf8_lossy(&response.body[..response.body.len().min(6)]);
    let query_ok = response.status == 200
        && response
            .content_type
            .contains("application/vnd.apache.arrow")
        && response.body.starts_with(b"ARROW1");
    println!(
        "{{\"queryOk\":{},\"status\":{},\"contentType\":\"{}\",\"bodyPrefix\":\"{}\"}}",
        query_ok,
        response.status,
        json_escape(&response.content_type),
        json_escape(&body_prefix)
    );
    if query_ok {
        Ok(())
    } else {
        Err("query response did not match Arrow smoke expectations".to_string())
    }
}

fn main() {
    if let Err(err) = run() {
        eprintln!("{err}");
        process::exit(1);
    }
}
