package ir.yasir.diag;

import java.nio.charset.StandardCharsets;

final class DiagWire {
  private DiagWire() {}
  static byte[] frame(String command, boolean crlf) {
    if(command==null || command.length()==0 || command.length()>64)
      throw new IllegalArgumentException("Invalid diagnostic command");
    for(int i=0;i<command.length();i++){
      char ch=command.charAt(i);
      if(ch<32 || ch>126)throw new IllegalArgumentException("Non-ASCII diagnostic command");
    }
    return (command+(crlf?"\r\n":"\n")).getBytes(StandardCharsets.US_ASCII);
  }
}
