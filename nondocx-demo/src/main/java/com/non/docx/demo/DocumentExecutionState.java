package com.non.docx.demo;

/** 单次 SubAgent 实施的服务端状态。 */
final class DocumentExecutionState {

  boolean delegated;
  boolean saved;
  boolean failed;
  boolean cancelled;
  String qualityReport = "";
}
