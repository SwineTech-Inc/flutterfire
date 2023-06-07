// Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
// Autogenerated from Pigeon (v10.0.0), do not edit directly.
// See also: https://pub.dev/packages/pigeon

#ifndef PIGEON_MESSAGES_G_H_
#define PIGEON_MESSAGES_G_H_
#include <flutter/basic_message_channel.h>
#include <flutter/binary_messenger.h>
#include <flutter/encodable_value.h>
#include <flutter/standard_message_codec.h>

#include <map>
#include <optional>
#include <string>

namespace firebase_auth_windows {


// Generated class from Pigeon.

class FlutterError {
 public:
  explicit FlutterError(const std::string& code)
    : code_(code) {}
  explicit FlutterError(const std::string& code, const std::string& message)
    : code_(code), message_(message) {}
  explicit FlutterError(const std::string& code, const std::string& message, const flutter::EncodableValue& details)
    : code_(code), message_(message), details_(details) {}

  const std::string& code() const { return code_; }
  const std::string& message() const { return message_; }
  const flutter::EncodableValue& details() const { return details_; }

 private:
  std::string code_;
  std::string message_;
  flutter::EncodableValue details_;
};

template<class T> class ErrorOr {
 public:
  ErrorOr(const T& rhs) : v_(rhs) {}
  ErrorOr(const T&& rhs) : v_(std::move(rhs)) {}
  ErrorOr(const FlutterError& rhs) : v_(rhs) {}
  ErrorOr(const FlutterError&& rhs) : v_(std::move(rhs)) {}

  bool has_error() const { return std::holds_alternative<FlutterError>(v_); }
  const T& value() const { return std::get<T>(v_); };
  const FlutterError& error() const { return std::get<FlutterError>(v_); };

 private:
  friend class FirebaseAuthHostApi;
  friend class FirebaseAuthUserHostApi;
  friend class MultiFactorUserHostApi;
  friend class GenerateInterfaces;
  ErrorOr() = default;
  T TakeValue() && { return std::get<T>(std::move(v_)); }

  std::variant<T, FlutterError> v_;
};


// The type of operation that generated the action code from calling
// [checkActionCode].
enum class ActionCodeInfoOperation {
  // Unknown operation.
  unknown = 0,
  // Password reset code generated via [sendPasswordResetEmail].
  passwordReset = 1,
  // Email verification code generated via [User.sendEmailVerification].
  verifyEmail = 2,
  // Email change revocation code generated via [User.updateEmail].
  recoverEmail = 3,
  // Email sign in code generated via [sendSignInLinkToEmail].
  emailSignIn = 4,
  // Verify and change email code generated via [User.verifyBeforeUpdateEmail].
  verifyAndChangeEmail = 5,
  // Action code for reverting second factor addition.
  revertSecondFactorAddition = 6
};

// Generated class from Pigeon that represents data sent in messages.
class PigeonFirebaseApp {
 public:
  // Constructs an object setting all non-nullable fields.
  explicit PigeonFirebaseApp(const std::string& app_name);

  // Constructs an object setting all fields.
  explicit PigeonFirebaseApp(
    const std::string& app_name,
    const std::string* tenant_id);

  const std::string& app_name() const;
  void set_app_name(std::string_view value_arg);

  const std::string* tenant_id() const;
  void set_tenant_id(const std::string_view* value_arg);
  void set_tenant_id(std::string_view value_arg);


 private:
  static PigeonFirebaseApp FromEncodableList(const flutter::EncodableList& list);
  flutter::EncodableList ToEncodableList() const;
  friend class FirebaseAuthHostApi;
  friend class FirebaseAuthHostApiCodecSerializer;
  friend class FirebaseAuthUserHostApi;
  friend class FirebaseAuthUserHostApiCodecSerializer;
  friend class MultiFactorUserHostApi;
  friend class MultiFactorUserHostApiCodecSerializer;
  friend class GenerateInterfaces;
  friend class GenerateInterfacesCodecSerializer;
  std::string app_name_;
  std::optional<std::string> tenant_id_;

};

class FirebaseAuthHostApiCodecSerializer : public flutter::StandardCodecSerializer {
 public:
  FirebaseAuthHostApiCodecSerializer();
  inline static FirebaseAuthHostApiCodecSerializer& GetInstance() {
    static FirebaseAuthHostApiCodecSerializer sInstance;
    return sInstance;
  }

  void WriteValue(
    const flutter::EncodableValue& value,
    flutter::ByteStreamWriter* stream) const override;

 protected:
  flutter::EncodableValue ReadValueOfType(
    uint8_t type,
    flutter::ByteStreamReader* stream) const override;

};

// Generated interface from Pigeon that represents a handler of messages from Flutter.
class FirebaseAuthHostApi {
 public:
  FirebaseAuthHostApi(const FirebaseAuthHostApi&) = delete;
  FirebaseAuthHostApi& operator=(const FirebaseAuthHostApi&) = delete;
  virtual ~FirebaseAuthHostApi() {}
  virtual void RegisterIdTokenListener(
    const PigeonFirebaseApp& app,
    std::function<void(ErrorOr<std::string> reply)> result) = 0;
  virtual void RegisterAuthStateListener(
    const PigeonFirebaseApp& app,
    std::function<void(ErrorOr<std::string> reply)> result) = 0;
  virtual void UseEmulator(
    const PigeonFirebaseApp& app,
    const std::string& host,
    int64_t port,
    std::function<void(std::optional<FlutterError> reply)> result) = 0;
  virtual void ApplyActionCode(
    const PigeonFirebaseApp& app,
    const std::string& code,
    std::function<void(std::optional<FlutterError> reply)> result) = 0;

  // The codec used by FirebaseAuthHostApi.
  static const flutter::StandardMessageCodec& GetCodec();
  // Sets up an instance of `FirebaseAuthHostApi` to handle messages through the `binary_messenger`.
  static void SetUp(
    flutter::BinaryMessenger* binary_messenger,
    FirebaseAuthHostApi* api);
  static flutter::EncodableValue WrapError(std::string_view error_message);
  static flutter::EncodableValue WrapError(const FlutterError& error);

 protected:
  FirebaseAuthHostApi() = default;

};
// Generated interface from Pigeon that represents a handler of messages from Flutter.
class FirebaseAuthUserHostApi {
 public:
  FirebaseAuthUserHostApi(const FirebaseAuthUserHostApi&) = delete;
  FirebaseAuthUserHostApi& operator=(const FirebaseAuthUserHostApi&) = delete;
  virtual ~FirebaseAuthUserHostApi() {}

  // The codec used by FirebaseAuthUserHostApi.
  static const flutter::StandardMessageCodec& GetCodec();
  // Sets up an instance of `FirebaseAuthUserHostApi` to handle messages through the `binary_messenger`.
  static void SetUp(
    flutter::BinaryMessenger* binary_messenger,
    FirebaseAuthUserHostApi* api);
  static flutter::EncodableValue WrapError(std::string_view error_message);
  static flutter::EncodableValue WrapError(const FlutterError& error);

 protected:
  FirebaseAuthUserHostApi() = default;

};
// Generated interface from Pigeon that represents a handler of messages from Flutter.
class MultiFactorUserHostApi {
 public:
  MultiFactorUserHostApi(const MultiFactorUserHostApi&) = delete;
  MultiFactorUserHostApi& operator=(const MultiFactorUserHostApi&) = delete;
  virtual ~MultiFactorUserHostApi() {}

  // The codec used by MultiFactorUserHostApi.
  static const flutter::StandardMessageCodec& GetCodec();
  // Sets up an instance of `MultiFactorUserHostApi` to handle messages through the `binary_messenger`.
  static void SetUp(
    flutter::BinaryMessenger* binary_messenger,
    MultiFactorUserHostApi* api);
  static flutter::EncodableValue WrapError(std::string_view error_message);
  static flutter::EncodableValue WrapError(const FlutterError& error);

 protected:
  MultiFactorUserHostApi() = default;

};
// Only used to generate the object interface that are use outside of the Pigeon interface
//
// Generated interface from Pigeon that represents a handler of messages from Flutter.
class GenerateInterfaces {
 public:
  GenerateInterfaces(const GenerateInterfaces&) = delete;
  GenerateInterfaces& operator=(const GenerateInterfaces&) = delete;
  virtual ~GenerateInterfaces() {}

  // The codec used by GenerateInterfaces.
  static const flutter::StandardMessageCodec& GetCodec();
  // Sets up an instance of `GenerateInterfaces` to handle messages through the `binary_messenger`.
  static void SetUp(
    flutter::BinaryMessenger* binary_messenger,
    GenerateInterfaces* api);
  static flutter::EncodableValue WrapError(std::string_view error_message);
  static flutter::EncodableValue WrapError(const FlutterError& error);

 protected:
  GenerateInterfaces() = default;

};
}  // namespace firebase_auth_windows
#endif  // PIGEON_MESSAGES_G_H_