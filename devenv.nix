{
  pkgs,
  lib,
  config,
  inputs,
  ...
}:

{
  packages = with pkgs; [
    android-tools
    nodejs
    scrcpy
  ];
}
