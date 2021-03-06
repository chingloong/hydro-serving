package io.hydrosphere.serving.manager.model.api

import com.google.protobuf.ByteString
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.model.api.validation.{PredictRequestContractValidator, SignatureValidator}
import io.hydrosphere.serving.tensorflow.types.DataType.{DT_BOOL, DT_FLOAT, DT_INT16, DT_STRING}
import org.scalatest.WordSpec
import spray.json._

class ValidationSpecs extends WordSpec {
  classOf[PredictRequestContractValidator].getSimpleName should {
    "convert" when {
      "flat json is compatible with contract" in {
        val input =
          """
            |{
            | "age": 2,
            | "name": "Vasya",
            | "isEmployed": true,
            | "features": [1.0, 0.0, 0.5, 0.1]
            |}
          """.stripMargin.parseJson.asJsObject


        val signature = ModelSignature(
          signatureName = "test",
          inputs = Seq(
            ContractBuilders.simpleTensorModelField("name", DT_STRING, None),
            ContractBuilders.simpleTensorModelField("age", DT_INT16, None),
            ContractBuilders.simpleTensorModelField("isEmployed", DT_BOOL, None),
            ContractBuilders.simpleTensorModelField("features", DT_FLOAT, Some(Seq(4L)))
          )
        )

        val validator = new SignatureValidator(signature)
        val result = validator.convert(input).right.get

        assert(result("age").intVal === Seq(2))
        assert(result("name").stringVal === Seq(ByteString.copyFromUtf8("Vasya")))
        assert(result("isEmployed").boolVal === Seq(true))
        assert(result("features").floatVal === Seq(1f, 0f, .5f, .1f))
      }

      "nested json is compatible with contract" in {
        val input =
          """
            |{
            | "isOk": true,
            | "person": {
            |   "name": "Vasya",
            |   "age": 18,
            |   "isEmployed": true,
            |   "features": [1.0, 0.0, 0.5, 0.1]
            | }
            |}
          """.stripMargin.parseJson.asJsObject

        val signature = ModelSignature(
          signatureName = "test",
          inputs = Seq(
            ContractBuilders.simpleTensorModelField("isOk", DT_BOOL, None),
            ContractBuilders.complexField("person",
              Seq(
                ContractBuilders.simpleTensorModelField("name", DT_STRING, None),
                ContractBuilders.simpleTensorModelField("age", DT_INT16, None),
                ContractBuilders.simpleTensorModelField("isEmployed", DT_BOOL, None),
                ContractBuilders.simpleTensorModelField("features", DT_FLOAT, Some(Seq(4L)))
              )
            )
          )
        )

        val validator = new SignatureValidator(signature)
        val result = validator.convert(input).right.get

        assert(result("isOk").boolVal === Seq(true))

        val person = result("person").mapVal
        assert(person("age").intVal === Seq(18))
        assert(person("name").stringVal === Seq(ByteString.copyFromUtf8("Vasya")))
        assert(person("isEmployed").boolVal === Seq(true))
        assert(person("features").floatVal === Seq(1f, 0f, .5f, .1f))
      }
    }

    "fail" when {
      "flat json is incompatible with contract" in {
        val input =
          """
            |{
            | "age": 2,
            | "name": "Vasya",
            | "isEmployed": true
            |}
          """.stripMargin.parseJson.asJsObject

        val signature = ModelSignature(
          signatureName = "test",
          inputs = Seq(
            ContractBuilders.simpleTensorModelField("name", DT_STRING, None),
            ContractBuilders.simpleTensorModelField("birthday", DT_STRING, None)
          )
        )

        val validator = new SignatureValidator(signature)
        val result = validator.convert(input)

        assert(result.isLeft, result)
        assert(result.left.get.getMessage.contains("Couldn't find 'birthday' field"))
      }

      "nested json is incompatible with contract" in {
        val input =
          """
            |{
            | "isOk": true,
            | "person": {
            |   "name": "Vasya",
            |   "age": 18,
            |   "isEmployed": true,
            |   "features": [1.0, 0.0, 0.5, 0.1]
            | }
            |}
          """.stripMargin.parseJson.asJsObject

        val signature = ModelSignature(
          signatureName = "test",
          inputs = Seq(
            ContractBuilders.simpleTensorModelField("isOk", DT_BOOL, None),
            ContractBuilders.complexField("person",
              Seq(
                ContractBuilders.simpleTensorModelField("surname", DT_STRING, None),
                ContractBuilders.simpleTensorModelField("age", DT_INT16, None),
                ContractBuilders.simpleTensorModelField("isEmployed", DT_BOOL, None),
                ContractBuilders.simpleTensorModelField("features", DT_FLOAT, Some(Seq(4L)))
              )
            )
          )
        )

        val validator = new SignatureValidator(signature)
        val result = validator.convert(input)

        assert(result.isLeft, result)
        val errorMsg = result.left.get.getMessage
        assert(errorMsg contains "Errors while validating subfields for 'person' field")
        assert(errorMsg contains "Couldn't find 'surname' field")
      }
    }
  }
}
