package coop.rchain.casper.util

import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.helper.BlockGenerator._
import coop.rchain.casper.helper.BlockUtil.generateValidator
import coop.rchain.casper.helper.{BlockDagStorageFixture, BlockGenerator}
import coop.rchain.shared.scalatestcontrib.AnyShouldF
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

class CasperUtilTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {

  "isInMainChain" should "classify appropriately" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        genesis <- createGenesis[Task](blockStore = blockStore)
        b2      <- createBlock[Task](Seq(genesis.blockHash), genesis, blockStore = blockStore)
        b3      <- createBlock[Task](Seq(b2.blockHash), genesis, blockStore = blockStore)

        dag <- blockDagStorage.getRepresentation

        _      <- dag.isInMainChain(genesis.blockHash, b3.blockHash) shouldBeF true
        _      <- dag.isInMainChain(b2.blockHash, b3.blockHash) shouldBeF true
        _      <- dag.isInMainChain(b3.blockHash, b2.blockHash) shouldBeF false
        result <- dag.isInMainChain(b3.blockHash, genesis.blockHash) shouldBeF false
      } yield result
  }

  "isInMainChain" should "classify diamond DAGs appropriately" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        genesis <- createGenesis[Task](blockStore = blockStore)
        b2      <- createBlock[Task](Seq(genesis.blockHash), genesis, blockStore = blockStore)
        b3      <- createBlock[Task](Seq(genesis.blockHash), genesis, blockStore = blockStore)
        b4      <- createBlock[Task](Seq(b2.blockHash, b3.blockHash), genesis, blockStore = blockStore)

        dag <- blockDagStorage.getRepresentation

        _      <- dag.isInMainChain(genesis.blockHash, b2.blockHash) shouldBeF true
        _      <- dag.isInMainChain(genesis.blockHash, b3.blockHash) shouldBeF true
        _      <- dag.isInMainChain(genesis.blockHash, b4.blockHash) shouldBeF true
        _      <- dag.isInMainChain(b2.blockHash, b4.blockHash) shouldBeF true
        result <- dag.isInMainChain(b3.blockHash, b4.blockHash) shouldBeF false
      } yield result
  }

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "isInMainChain" should "classify complicated chains appropriately" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v1 = generateValidator("Validator One")
      val v2 = generateValidator("Validator Two")

      for {
        genesis <- createGenesis[Task](blockStore = blockStore)
        b2      <- createBlock[Task](Seq(genesis.blockHash), genesis, v2, blockStore = blockStore)
        b3      <- createBlock[Task](Seq(genesis.blockHash), genesis, v1, blockStore = blockStore)
        b4      <- createBlock[Task](Seq(b2.blockHash), genesis, v2, blockStore = blockStore)
        b5      <- createBlock[Task](Seq(b2.blockHash), genesis, v1, blockStore = blockStore)
        b6      <- createBlock[Task](Seq(b4.blockHash), genesis, v2, blockStore = blockStore)
        b7      <- createBlock[Task](Seq(b4.blockHash), genesis, v1, blockStore = blockStore)
        b8      <- createBlock[Task](Seq(b7.blockHash), genesis, v1, blockStore = blockStore)

        dag <- blockDagStorage.getRepresentation

        _      <- dag.isInMainChain(genesis.blockHash, b2.blockHash) shouldBeF true
        _      <- dag.isInMainChain(b2.blockHash, b3.blockHash) shouldBeF false
        _      <- dag.isInMainChain(b3.blockHash, b4.blockHash) shouldBeF false
        _      <- dag.isInMainChain(b4.blockHash, b5.blockHash) shouldBeF false
        _      <- dag.isInMainChain(b5.blockHash, b6.blockHash) shouldBeF false
        _      <- dag.isInMainChain(b6.blockHash, b7.blockHash) shouldBeF false
        _      <- dag.isInMainChain(b7.blockHash, b8.blockHash) shouldBeF true
        _      <- dag.isInMainChain(b2.blockHash, b6.blockHash) shouldBeF true
        _      <- dag.isInMainChain(b2.blockHash, b8.blockHash) shouldBeF true
        result <- dag.isInMainChain(b4.blockHash, b2.blockHash) shouldBeF false
      } yield result
  }
}
