{ pkgs, ... }:
{
  languages.clojure.enable = true;
  languages.opentofu.enable = true;
  packages = with pkgs; [
    ansible babashka curl doctl jq openssh rclone unzip
    openjdk21 netcat-openbsd
  ];
}
